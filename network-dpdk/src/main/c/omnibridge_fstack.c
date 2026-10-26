/*
 * Thin C shim over F-Stack (FreeBSD TCP/IP on DPDK, library mode) for the omni-bridge
 * event loop. The Java side (FfmFstackNative) binds these ob_* functions with the FFM API;
 * keeping struct layouts (sockaddr, epoll_event) on this side means the Java code needs no
 * memory layouts beyond flat ints and the (fd, events) pairs of ob_epoll_wait.
 *
 * Build on the host (see scripts/deploy/dpdk/setup-fstack-host.sh):
 *   gcc -O2 -fPIC -shared -o libomnibridge-fstack.so omnibridge_fstack.c \
 *       -I$FF_PATH/lib -L$FF_PATH/lib -Wl,--whole-archive -lfstack -Wl,--no-whole-archive \
 *       $(pkg-config --libs libdpdk) -lrt -lm -ldl -lcrypto -lpthread -lnuma
 *
 * Every function must be called from the F-Stack lcore thread (the one that called ob_init
 * and ob_run), which is the event loop thread.
 */
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>

#include "ff_api.h"
#include "ff_epoll.h"

#define OB_MAX_EVENTS 256

static void (*ob_callback)(void);
static struct epoll_event ob_events[OB_MAX_EVENTS];

int ob_init(const char *conf) {
    char conf_arg[1024];
    snprintf(conf_arg, sizeof conf_arg, "--conf=%s", conf);
    char *argv[] = { "omnibridge", conf_arg, "--proc-type=primary", "--proc-id=0", NULL };
    return ff_init(4, argv);
}

static int ob_loop(void *arg) {
    (void) arg;
    ob_callback();
    return 0;
}

void ob_run(void (*cb)(void)) {
    ob_callback = cb;
    ff_run(ob_loop, NULL);
}

/*
 * Asked to stop: deliberately does nothing. ff_stop_run() makes ff_dpdk_run() return through
 * ff_unload_config() and rte_eal_cleanup(), and in this F-Stack revision ff_unload_config()
 * frees a config string twice ("free(): double free detected in tcache 2"), aborting the
 * process at exit. The stack keeps polling on its lcore until the process exits, which
 * releases the NIC; the event loop's stop() gives up joining the thread after its timeout.
 */
void ob_stop(void) {
}

int ob_errno(void) {
    return errno;
}

int ob_epoll_create(void) {
    return ff_epoll_create(0);
}

/* A non-blocking TCP socket with Nagle off, or -1 (errno set). */
int ob_socket(void) {
    int fd = ff_socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        return -1;
    }
    int on = 1;
    if (ff_ioctl(fd, FIONBIO, &on) < 0 ||
        ff_setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &on, sizeof on) < 0) {
        int saved = errno;
        ff_close(fd);
        errno = saved;
        return -1;
    }
    return fd;
}

static void ob_fill(struct sockaddr_in *addr, uint32_t ip_be, uint16_t port_be) {
    memset(addr, 0, sizeof *addr);
    addr->sin_family = AF_INET;
    addr->sin_addr.s_addr = ip_be;
    addr->sin_port = port_be;
}

/* 0 when connected at once, 1 when in progress, -1 on failure (errno set). */
int ob_connect(int fd, uint32_t ip_be, uint16_t port_be) {
    struct sockaddr_in addr;
    ob_fill(&addr, ip_be, port_be);
    if (ff_connect(fd, (struct linux_sockaddr *) &addr, sizeof addr) == 0) {
        return 0;
    }
    return errno == EINPROGRESS ? 1 : -1;
}

int ob_bind_listen(int fd, uint32_t ip_be, uint16_t port_be, int backlog) {
    struct sockaddr_in addr;
    ob_fill(&addr, ip_be, port_be);
    int on = 1;
    ff_setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &on, sizeof on);
    if (ff_bind(fd, (struct linux_sockaddr *) &addr, sizeof addr) < 0) {
        return -1;
    }
    return ff_listen(fd, backlog);
}

/* The accepted fd (non-blocking, Nagle off) with the peer's address, -1 if none (EAGAIN) or on error. */
int ob_accept(int fd, uint32_t *ip_be, uint16_t *port_be) {
    struct sockaddr_in addr;
    socklen_t len = sizeof addr;
    int afd = ff_accept(fd, (struct linux_sockaddr *) &addr, &len);
    if (afd < 0) {
        return -1;
    }
    int on = 1;
    ff_ioctl(afd, FIONBIO, &on);
    ff_setsockopt(afd, IPPROTO_TCP, TCP_NODELAY, &on, sizeof on);
    *ip_be = addr.sin_addr.s_addr;
    *port_be = addr.sin_port;
    return afd;
}

int ob_epoll_ctl(int epfd, int op, int fd, uint32_t events) {
    struct epoll_event ev;
    memset(&ev, 0, sizeof ev);
    ev.events = events;
    ev.data.fd = fd;
    return ff_epoll_ctl(epfd, op, fd, &ev);
}

/* Writes (fd, events) int32 pairs into out, at most max pairs; returns the count or -1. */
int ob_epoll_wait(int epfd, int32_t *out, int max) {
    if (max > OB_MAX_EVENTS) {
        max = OB_MAX_EVENTS;
    }
    int n = ff_epoll_wait(epfd, ob_events, max, 0);
    for (int i = 0; i < n; i++) {
        out[2 * i] = ob_events[i].data.fd;
        out[2 * i + 1] = (int32_t) ob_events[i].events;
    }
    return n;
}

long ob_read(int fd, void *buf, size_t len) {
    return ff_read(fd, buf, len);
}

long ob_write(int fd, const void *buf, size_t len) {
    return ff_write(fd, buf, len);
}

int ob_close(int fd) {
    return ff_close(fd);
}

/* The pending error of a socket after a non-blocking connect completed (0 = connected). */
int ob_so_error(int fd) {
    int err = 0;
    socklen_t len = sizeof err;
    if (ff_getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &len) < 0) {
        return errno;
    }
    return err;
}

int ob_local_addr(int fd, uint32_t *ip_be, uint16_t *port_be) {
    struct sockaddr_in addr;
    socklen_t len = sizeof addr;
    if (ff_getsockname(fd, (struct linux_sockaddr *) &addr, &len) < 0) {
        return -1;
    }
    *ip_be = addr.sin_addr.s_addr;
    *port_be = addr.sin_port;
    return 0;
}

int ob_peer_addr(int fd, uint32_t *ip_be, uint16_t *port_be) {
    struct sockaddr_in addr;
    socklen_t len = sizeof addr;
    if (ff_getpeername(fd, (struct linux_sockaddr *) &addr, &len) < 0) {
        return -1;
    }
    *ip_be = addr.sin_addr.s_addr;
    *port_be = addr.sin_port;
    return 0;
}
