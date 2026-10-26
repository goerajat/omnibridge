/*
 * pingpong.c: a DPDK UDP ping-pong between two ENA ports on EC2.
 *
 *   reflect mode: every UDP packet received on the port is sent back with
 *                 Ethernet addresses, IPv4 addresses and UDP ports swapped
 *                 (what testpmd's 5tswap does), from the same lcore.
 *   send mode:    sends one UDP packet of --size payload bytes, spins on the
 *                 receive queue until the reply with the same sequence number
 *                 comes back, records the round trip from rte_rdtsc, then
 *                 waits until the next packet is due (--interval-us). Prints
 *                 min, p50, p90, p99, p99.9, p99.99, max and the lost count.
 *
 * Both sides run on one lcore (-l <cpu>), one Rx queue and one Tx queue, no
 * offloads. The destination MAC is passed in (no ARP): EC2's fabric delivers
 * on the ENI's own MAC and private IP, so both must be the real ones.
 *
 *   gcc -O2 -o pingpong pingpong.c $(pkg-config --cflags --libs libdpdk)
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <inttypes.h>
#include <signal.h>
#include <arpa/inet.h>

#include <rte_eal.h>
#include <rte_ethdev.h>
#include <rte_mbuf.h>
#include <rte_ether.h>
#include <rte_ip.h>
#include <rte_udp.h>
#include <rte_cycles.h>
#include <rte_lcore.h>

#define RX_RING 1024
#define TX_RING 1024
#define BURST 32

static volatile int stop;
static void on_sig(int s) { (void)s; stop = 1; }

struct opts {
    int reflect;
    uint32_t src_ip, dst_ip;
    struct rte_ether_addr dst_mac;
    uint16_t port;
    int size;
    long count, warmup;
    long interval_us;
    long timeout_ms;
};

static int cmp_d(const void *a, const void *b) {
    double x = *(const double *)a, y = *(const double *)b;
    return x < y ? -1 : x > y;
}

static double pct(double *v, long n, double p) {
    long i = (long)(p / 100.0 * (n - 1) + 0.5);
    if (i < 0) i = 0; if (i >= n) i = n - 1;
    return v[i];
}

static int port_init(uint16_t port, struct rte_mempool *pool) {
    struct rte_eth_conf conf;
    struct rte_eth_dev_info info;
    uint16_t rx = RX_RING, tx = TX_RING;
    int r;
    memset(&conf, 0, sizeof conf);
    if ((r = rte_eth_dev_info_get(port, &info)) != 0) return r;
    if ((r = rte_eth_dev_configure(port, 1, 1, &conf)) != 0) return r;
    if ((r = rte_eth_dev_adjust_nb_rx_tx_desc(port, &rx, &tx)) != 0) return r;
    if ((r = rte_eth_rx_queue_setup(port, 0, rx, rte_eth_dev_socket_id(port), NULL, pool)) < 0) return r;
    if ((r = rte_eth_tx_queue_setup(port, 0, tx, rte_eth_dev_socket_id(port), NULL)) < 0) return r;
    if ((r = rte_eth_dev_start(port)) < 0) return r;
    return 0;
}

static void swap_and_send(uint16_t port, struct rte_mbuf **pk, uint16_t n) {
    uint16_t i;
    for (i = 0; i < n; i++) {
        struct rte_ether_hdr *eh = rte_pktmbuf_mtod(pk[i], struct rte_ether_hdr *);
        struct rte_ipv4_hdr *ih;
        struct rte_udp_hdr *uh;
        struct rte_ether_addr tmp;
        uint32_t tip; uint16_t tp;
        if (eh->ether_type != rte_cpu_to_be_16(RTE_ETHER_TYPE_IPV4)) { rte_pktmbuf_free(pk[i]); pk[i] = NULL; continue; }
        ih = (struct rte_ipv4_hdr *)(eh + 1);
        if (ih->next_proto_id != IPPROTO_UDP) { rte_pktmbuf_free(pk[i]); pk[i] = NULL; continue; }
        uh = (struct rte_udp_hdr *)((uint8_t *)ih + rte_ipv4_hdr_len(ih));
        rte_ether_addr_copy(&eh->src_addr, &tmp);
        rte_ether_addr_copy(&eh->dst_addr, &eh->src_addr);
        rte_ether_addr_copy(&tmp, &eh->dst_addr);
        tip = ih->src_addr; ih->src_addr = ih->dst_addr; ih->dst_addr = tip;
        tp = uh->src_port; uh->src_port = uh->dst_port; uh->dst_port = tp;
        uh->dgram_cksum = 0;          /* optional for IPv4 */
        /* the IPv4 header checksum is unchanged by swapping two 32-bit fields */
    }
    {
        uint16_t j = 0, k;
        for (k = 0; k < n; k++) if (pk[k]) pk[j++] = pk[k];
        if (j) {
            uint16_t sent = rte_eth_tx_burst(port, 0, pk, j);
            for (k = sent; k < j; k++) rte_pktmbuf_free(pk[k]);
        }
    }
}

static void reflect(uint16_t port) {
    struct rte_mbuf *pk[BURST];
    uint64_t n = 0, last = 0, hz = rte_get_tsc_hz();
    uint64_t t = rte_rdtsc();
    printf("reflecting on port %u, lcore %u\n", port, rte_lcore_id());
    fflush(stdout);
    while (!stop) {
        uint16_t got = rte_eth_rx_burst(port, 0, pk, BURST);
        if (got) { n += got; swap_and_send(port, pk, got); }
        else if (rte_rdtsc() - t > 10 * hz) {
            t = rte_rdtsc();
            if (n != last) { printf("reflected %" PRIu64 "\n", n); fflush(stdout); last = n; }
        }
    }
    printf("reflected %" PRIu64 " packets\n", n);
}

static void build(struct rte_mbuf *m, const struct opts *o, const struct rte_ether_addr *src_mac, uint64_t seq) {
    int plen = o->size;
    int tot = sizeof(struct rte_ether_hdr) + sizeof(struct rte_ipv4_hdr) + sizeof(struct rte_udp_hdr) + plen;
    struct rte_ether_hdr *eh = (struct rte_ether_hdr *)rte_pktmbuf_append(m, tot);
    struct rte_ipv4_hdr *ih = (struct rte_ipv4_hdr *)(eh + 1);
    struct rte_udp_hdr *uh = (struct rte_udp_hdr *)(ih + 1);
    uint64_t *payload = (uint64_t *)(uh + 1);
    rte_ether_addr_copy(&o->dst_mac, &eh->dst_addr);
    rte_ether_addr_copy(src_mac, &eh->src_addr);
    eh->ether_type = rte_cpu_to_be_16(RTE_ETHER_TYPE_IPV4);
    memset(ih, 0, sizeof *ih);
    ih->version_ihl = RTE_IPV4_VHL_DEF;
    ih->total_length = rte_cpu_to_be_16(sizeof *ih + sizeof *uh + plen);
    ih->time_to_live = 64;
    ih->next_proto_id = IPPROTO_UDP;
    ih->src_addr = o->src_ip;
    ih->dst_addr = o->dst_ip;
    ih->hdr_checksum = rte_ipv4_cksum(ih);
    uh->src_port = rte_cpu_to_be_16(o->port);
    uh->dst_port = rte_cpu_to_be_16(o->port);
    uh->dgram_len = rte_cpu_to_be_16(sizeof *uh + plen);
    uh->dgram_cksum = 0;
    memset(payload, 0, plen);
    payload[0] = seq;
}

static int reply_seq(struct rte_mbuf *m, const struct opts *o, uint64_t *seq) {
    struct rte_ether_hdr *eh = rte_pktmbuf_mtod(m, struct rte_ether_hdr *);
    struct rte_ipv4_hdr *ih; struct rte_udp_hdr *uh;
    if (eh->ether_type != rte_cpu_to_be_16(RTE_ETHER_TYPE_IPV4)) return 0;
    ih = (struct rte_ipv4_hdr *)(eh + 1);
    if (ih->next_proto_id != IPPROTO_UDP || ih->src_addr != o->dst_ip) return 0;
    uh = (struct rte_udp_hdr *)((uint8_t *)ih + rte_ipv4_hdr_len(ih));
    if (uh->dst_port != rte_cpu_to_be_16(o->port)) return 0;
    *seq = *(uint64_t *)(uh + 1);
    return 1;
}

static void send_loop(uint16_t port, struct rte_mempool *pool, const struct opts *o) {
    struct rte_ether_addr src_mac;
    uint64_t hz = rte_get_tsc_hz();
    uint64_t interval = o->interval_us * hz / 1000000, timeout = o->timeout_ms * hz / 1000;
    long total = o->warmup + o->count, i, lost = 0, n = 0;
    double *rtt = malloc(sizeof(double) * o->count);
    uint64_t next;
    char sip[32], dip[32];
    struct in_addr a;
    rte_eth_macaddr_get(port, &src_mac);
    a.s_addr = o->src_ip; strcpy(sip, inet_ntoa(a));
    a.s_addr = o->dst_ip; strcpy(dip, inet_ntoa(a));
    printf("sending %ld warmup + %ld packets, %d B payload, every %ld us, %s -> %s, lcore %u, tsc %" PRIu64 " Hz\n",
           o->warmup, o->count, o->size, o->interval_us, sip, dip, rte_lcore_id(), hz);
    fflush(stdout);
    /* drain anything queued */
    { struct rte_mbuf *pk[BURST]; uint16_t g; while ((g = rte_eth_rx_burst(port, 0, pk, BURST))) { uint16_t k; for (k = 0; k < g; k++) rte_pktmbuf_free(pk[k]); } }
    next = rte_rdtsc();
    for (i = 0; i < total && !stop; i++) {
        struct rte_mbuf *m = rte_pktmbuf_alloc(pool);
        uint64_t t0, t1 = 0, seq = (uint64_t)i + 1;
        int got = 0;
        if (!m) rte_exit(EXIT_FAILURE, "mbuf alloc failed\n");
        build(m, o, &src_mac, seq);
        while (rte_rdtsc() < next) ;            /* spin until due */
        t0 = rte_rdtsc();
        if (rte_eth_tx_burst(port, 0, &m, 1) != 1) { rte_pktmbuf_free(m); rte_exit(EXIT_FAILURE, "tx failed\n"); }
        while (!got) {
            struct rte_mbuf *pk[BURST];
            uint16_t g = rte_eth_rx_burst(port, 0, pk, BURST), k;
            uint64_t now = rte_rdtsc();
            for (k = 0; k < g; k++) {
                uint64_t s;
                if (!got && reply_seq(pk[k], o, &s) && s == seq) { t1 = now; got = 1; }
                rte_pktmbuf_free(pk[k]);
            }
            if (!got && now - t0 > timeout) break;
        }
        if (got) { if (i >= o->warmup) rtt[n++] = (double)(t1 - t0) * 1e6 / hz; }
        else if (i >= o->warmup) lost++;
        next = t0 + interval;
    }
    if (n == 0) { printf("no replies\n"); return; }
    qsort(rtt, n, sizeof(double), cmp_d);
    {
        double sum = 0; long k; for (k = 0; k < n; k++) sum += rtt[k];
        printf("dpdk round trip (us), %ld samples, %ld lost:\n", n, lost);
        printf("  min %.2f  p50 %.2f  p90 %.2f  p99 %.2f  p99.9 %.2f  p99.99 %.2f  max %.2f  mean %.2f\n",
               rtt[0], pct(rtt, n, 50), pct(rtt, n, 90), pct(rtt, n, 99), pct(rtt, n, 99.9), pct(rtt, n, 99.99), rtt[n - 1], sum / n);
    }
    free(rtt);
}

int main(int argc, char **argv) {
    struct opts o;
    struct rte_mempool *pool;
    uint16_t port = 0;
    int r, i;
    r = rte_eal_init(argc, argv);
    if (r < 0) rte_exit(EXIT_FAILURE, "EAL init failed\n");
    argc -= r; argv += r;
    memset(&o, 0, sizeof o);
    o.port = 11111; o.size = 200; o.count = 100000; o.warmup = 20000; o.interval_us = 1000; o.timeout_ms = 200;
    for (i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--mode") && i + 1 < argc) o.reflect = !strcmp(argv[++i], "reflect");
        else if (!strcmp(argv[i], "--src-ip") && i + 1 < argc) inet_pton(AF_INET, argv[++i], &o.src_ip);
        else if (!strcmp(argv[i], "--dst-ip") && i + 1 < argc) inet_pton(AF_INET, argv[++i], &o.dst_ip);
        else if (!strcmp(argv[i], "--dst-mac") && i + 1 < argc) { if (rte_ether_unformat_addr(argv[++i], &o.dst_mac) != 0) rte_exit(EXIT_FAILURE, "bad mac\n"); }
        else if (!strcmp(argv[i], "--port") && i + 1 < argc) o.port = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--size") && i + 1 < argc) o.size = atoi(argv[++i]);
        else if (!strcmp(argv[i], "-n") && i + 1 < argc) o.count = atol(argv[++i]);
        else if (!strcmp(argv[i], "-w") && i + 1 < argc) o.warmup = atol(argv[++i]);
        else if (!strcmp(argv[i], "--interval-us") && i + 1 < argc) o.interval_us = atol(argv[++i]);
        else if (!strcmp(argv[i], "--timeout-ms") && i + 1 < argc) o.timeout_ms = atol(argv[++i]);
        else rte_exit(EXIT_FAILURE, "unknown arg %s\n", argv[i]);
    }
    if (rte_eth_dev_count_avail() == 0) rte_exit(EXIT_FAILURE, "no DPDK ports\n");
    pool = rte_pktmbuf_pool_create("pp", 8191, 256, 0, RTE_MBUF_DEFAULT_BUF_SIZE, rte_socket_id());
    if (!pool) rte_exit(EXIT_FAILURE, "mempool failed\n");
    if ((r = port_init(port, pool)) != 0) rte_exit(EXIT_FAILURE, "port init failed: %d\n", r);
    {
        struct rte_ether_addr mac; char buf[32];
        rte_eth_macaddr_get(port, &mac);
        rte_ether_format_addr(buf, sizeof buf, &mac);
        printf("port %u mac %s\n", port, buf);
    }
    signal(SIGINT, on_sig); signal(SIGTERM, on_sig);
    if (o.reflect) reflect(port); else send_loop(port, pool, &o);
    rte_eth_dev_stop(port);
    rte_eth_dev_close(port);
    rte_eal_cleanup();
    return 0;
}
