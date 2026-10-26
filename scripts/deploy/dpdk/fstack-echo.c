/*
 * TCP echo over F-Stack, no JVM: what FreeBSD's TCP on DPDK costs between two hosts on its
 * own, next to the raw DPDK UDP ping-pong (pingpong.c) and the FIX round trip on the same
 * stack. One process per host, each the F-Stack primary on its lcore (fstack.ini).
 *
 *   fstack-echo --conf /opt/fstack/fstack.ini --mode server --port 11111
 *   fstack-echo --conf /opt/fstack/fstack.ini --mode client --ip <server-ip> --port 11111
 *               [-n 100000] [-w 20000] [--interval-us 1000] [--size 200] [--timeout-ms 100]
 *
 * The client sends one message every interval, measures the round trip from the write to
 * the last echoed byte (rdtsc), and prints min / p50 / p90 / p99 / p99.9 / p99.99 / max /
 * mean plus the send slip against the intended schedule. Both sides exit through exit():
 * ff_dpdk_run's own return path double-frees its config in this F-Stack revision.
 *
 * Build on the host (setup-fstack-host.sh does it):
 *   gcc -O2 -o fstack-echo fstack-echo.c $(pkg-config --cflags libdpdk) -I/opt/fstack \
 *       -Wl,--whole-archive /opt/fstack/libfstack.a -Wl,--no-whole-archive \
 *       $(pkg-config --static --libs libdpdk) -lrt -lm -ldl -lcrypto -lpthread -lnuma
 */
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>

#include <rte_cycles.h>

#include "ff_api.h"
#include "ff_epoll.h"

#define MAX_EVENTS 64
#define BUF_SIZE (64 * 1024)

static const char *mode = "server";
static const char *ip = NULL;
static int port = 11111;
static long count = 100000, warmup = 20000;
static long interval_us = 1000;
static int size = 200;
static long timeout_ms = 100;

static int epfd, lfd = -1, fd = -1;
static char buf[BUF_SIZE];
static struct epoll_event events[MAX_EVENTS];

/* client state */
static enum { CONNECTING, CONNECTED, DONE } state = CONNECTING;
static uint64_t hz, interval_tsc, timeout_tsc;
static uint64_t next_send, t_send, t_intended;
static long sent, received_full, lost, idx;
static int outstanding, got;
static double *rtt, *slip;

static int cmp(const void *a, const void *b) {
    double x = *(const double *) a, y = *(const double *) b;
    return (x > y) - (x < y);
}

static double pct(double *v, long n, double p) {
    long i = (long) (p / 100.0 * (n - 1) + 0.5);
    return v[i < n ? i : n - 1];
}

static void report(const char *name, double *v, long n) {
    if (n == 0) {
        printf("%s: no samples\n", name);
        return;
    }
    qsort(v, n, sizeof *v, cmp);
    double sum = 0;
    for (long i = 0; i < n; i++) sum += v[i];
    printf("%s (us): count=%ld min=%.2f p50=%.2f p90=%.2f p99=%.2f p99.9=%.2f p99.99=%.2f max=%.2f mean=%.2f\n",
           name, n, v[0], pct(v, n, 50), pct(v, n, 90), pct(v, n, 99), pct(v, n, 99.9), pct(v, n, 99.99),
           v[n - 1], sum / n);
}

static int set_nonblock_nodelay(int s) {
    int on = 1;
    if (ff_ioctl(s, FIONBIO, &on) < 0) return -1;
    return ff_setsockopt(s, IPPROTO_TCP, TCP_NODELAY, &on, sizeof on);
}

static void add(int s, uint32_t ev) {
    struct epoll_event e;
    memset(&e, 0, sizeof e);
    e.events = ev;
    e.data.fd = s;
    if (ff_epoll_ctl(epfd, EPOLL_CTL_ADD, s, &e) < 0) {
        fprintf(stderr, "epoll_ctl add failed: errno %d\n", errno);
        exit(2);
    }
}

static void mod(int s, uint32_t ev) {
    struct epoll_event e;
    memset(&e, 0, sizeof e);
    e.events = ev;
    e.data.fd = s;
    ff_epoll_ctl(epfd, EPOLL_CTL_MOD, s, &e);
}

/* ---------------- server: echo everything back ---------------- */
static int server_loop(void *arg) {
    (void) arg;
    int n = ff_epoll_wait(epfd, events, MAX_EVENTS, 0);
    for (int i = 0; i < n; i++) {
        int s = events[i].data.fd;
        if (s == lfd) {
            struct sockaddr_in peer;
            socklen_t len = sizeof peer;
            int c = ff_accept(lfd, (struct linux_sockaddr *) &peer, &len);
            if (c >= 0) {
                set_nonblock_nodelay(c);
                add(c, EPOLLIN);
                printf("accepted fd %d from %s:%d\n", c, inet_ntoa(peer.sin_addr), ntohs(peer.sin_port));
                fflush(stdout);
            }
            continue;
        }
        if (events[i].events & (EPOLLERR | EPOLLHUP)) {
            ff_epoll_ctl(epfd, EPOLL_CTL_DEL, s, NULL);
            ff_close(s);
            continue;
        }
        if (events[i].events & EPOLLIN) {
            long r = ff_read(s, buf, sizeof buf);
            if (r <= 0) {
                if (r == 0 || (errno != EAGAIN && errno != EWOULDBLOCK)) {
                    ff_epoll_ctl(epfd, EPOLL_CTL_DEL, s, NULL);
                    ff_close(s);
                    printf("closed fd %d\n", s);
                    fflush(stdout);
                }
                continue;
            }
            long off = 0;
            while (off < r) {
                long w = ff_write(s, buf + off, r - off);
                if (w < 0) {
                    if (errno == EAGAIN || errno == EWOULDBLOCK) continue; /* spin: the socket drains on the next pass */
                    break;
                }
                off += w;
            }
        }
    }
    return 0;
}

/* ---------------- client: paced request/response ---------------- */
static void finish(void) {
    printf("sent=%ld received=%ld lost=%ld\n", sent, received_full, lost);
    report("F-Stack TCP echo rtt", rtt, idx);
    report("send slip (actual - intended)", slip, idx);
    fflush(stdout);
    exit(0);
}

static int client_loop(void *arg) {
    (void) arg;
    uint64_t now = rte_rdtsc();
    int n = ff_epoll_wait(epfd, events, MAX_EVENTS, 0);
    for (int i = 0; i < n; i++) {
        if (state == CONNECTING && (events[i].events & (EPOLLOUT | EPOLLERR | EPOLLHUP))) {
            int err = 0;
            socklen_t len = sizeof err;
            ff_getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &len);
            if (err != 0) {
                fprintf(stderr, "connect failed: errno %d\n", err);
                exit(3);
            }
            state = CONNECTED;
            mod(fd, EPOLLIN);
            printf("connected to %s:%d\n", ip, port);
            fflush(stdout);
            next_send = rte_rdtsc() + interval_tsc;
        } else if (state == CONNECTED && (events[i].events & EPOLLIN)) {
            long r = ff_read(fd, buf, sizeof buf);
            if (r > 0 && outstanding) {
                got += (int) r;
                if (got >= size) {
                    uint64_t t = rte_rdtsc();
                    if (sent > warmup) {
                        rtt[idx] = (double) (t - t_send) * 1e6 / hz;
                        slip[idx] = (double) (t_send - t_intended) * 1e6 / hz;
                        idx++;
                    }
                    received_full++;
                    outstanding = 0;
                    got = 0;
                }
            }
        }
    }
    if (state != CONNECTED) return 0;
    if (outstanding && now - t_send > timeout_tsc) {
        lost++;
        outstanding = 0;
        got = 0;
    }
    if (!outstanding && now >= next_send) {
        if (sent >= warmup + count) {
            finish();
        }
        t_intended = next_send;
        t_send = rte_rdtsc();
        long w = ff_write(fd, buf, size);
        if (w != size) {
            fprintf(stderr, "short write %ld (errno %d)\n", w, errno);
            exit(4);
        }
        sent++;
        outstanding = 1;
        got = 0;
        next_send += interval_tsc;
        if (sent == warmup) {
            printf("warmup done (%ld), measuring %ld\n", warmup, count);
            fflush(stdout);
        }
    }
    return 0;
}

int main(int argc, char **argv) {
    const char *conf = "/opt/fstack/fstack.ini";
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--conf") && i + 1 < argc) conf = argv[++i];
        else if (!strcmp(argv[i], "--mode") && i + 1 < argc) mode = argv[++i];
        else if (!strcmp(argv[i], "--ip") && i + 1 < argc) ip = argv[++i];
        else if (!strcmp(argv[i], "--port") && i + 1 < argc) port = atoi(argv[++i]);
        else if (!strcmp(argv[i], "-n") && i + 1 < argc) count = atol(argv[++i]);
        else if (!strcmp(argv[i], "-w") && i + 1 < argc) warmup = atol(argv[++i]);
        else if (!strcmp(argv[i], "--interval-us") && i + 1 < argc) interval_us = atol(argv[++i]);
        else if (!strcmp(argv[i], "--size") && i + 1 < argc) size = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--timeout-ms") && i + 1 < argc) timeout_ms = atol(argv[++i]);
        else { fprintf(stderr, "unknown argument %s\n", argv[i]); return 1; }
    }
    char conf_arg[1024];
    snprintf(conf_arg, sizeof conf_arg, "--conf=%s", conf);
    char *ff_argv[] = { argv[0], conf_arg, "--proc-type=primary", "--proc-id=0", NULL };
    if (ff_init(4, ff_argv) < 0) {
        fprintf(stderr, "ff_init failed\n");
        return 2;
    }
    epfd = ff_epoll_create(0);
    memset(buf, 'x', sizeof buf);

    if (!strcmp(mode, "server")) {
        lfd = ff_socket(AF_INET, SOCK_STREAM, 0);
        set_nonblock_nodelay(lfd);
        int on = 1;
        ff_setsockopt(lfd, SOL_SOCKET, SO_REUSEADDR, &on, sizeof on);
        struct sockaddr_in a;
        memset(&a, 0, sizeof a);
        a.sin_family = AF_INET;
        a.sin_port = htons(port);
        a.sin_addr.s_addr = htonl(INADDR_ANY);
        if (ff_bind(lfd, (struct linux_sockaddr *) &a, sizeof a) < 0 || ff_listen(lfd, 16) < 0) {
            fprintf(stderr, "bind/listen failed: errno %d\n", errno);
            return 2;
        }
        add(lfd, EPOLLIN);
        printf("echo server listening on %d\n", port);
        fflush(stdout);
        ff_run(server_loop, NULL);
        return 0;
    }

    if (ip == NULL) { fprintf(stderr, "--ip required for the client\n"); return 1; }
    hz = rte_get_tsc_hz();
    interval_tsc = hz * (uint64_t) interval_us / 1000000;
    timeout_tsc = hz * (uint64_t) timeout_ms / 1000;
    rtt = calloc(count + 1, sizeof *rtt);
    slip = calloc(count + 1, sizeof *slip);
    fd = ff_socket(AF_INET, SOCK_STREAM, 0);
    set_nonblock_nodelay(fd);
    struct sockaddr_in a;
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_port = htons(port);
    a.sin_addr.s_addr = inet_addr(ip);
    int rc = ff_connect(fd, (struct linux_sockaddr *) &a, sizeof a);
    if (rc < 0 && errno != EINPROGRESS) {
        fprintf(stderr, "connect failed: errno %d\n", errno);
        return 3;
    }
    add(fd, EPOLLOUT);
    printf("client: %ld warmup + %ld messages of %d bytes every %ld us to %s:%d\n",
           warmup, count, size, interval_us, ip, port);
    fflush(stdout);
    ff_run(client_loop, NULL);
    return 0;
}
