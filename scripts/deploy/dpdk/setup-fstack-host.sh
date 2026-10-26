#!/bin/bash
# Runs on a trading host: builds F-Stack (FreeBSD TCP/IP on DPDK, library mode) and the
# omni-bridge shim, writes /opt/fstack/fstack.ini for the second ENI, reserves hugepages
# and binds that ENI to vfio-pci (no-IOMMU). Idempotent: a finished build is reused.
#
#   setup-fstack-host.sh <eni-mac> <eni-ip> <subnet-cidr> <cpu>
#
# DPDK: F-Stack is built against the DPDK tree it ships (dpdk/ in its repository, the
# version it is developed on), built here into /opt/dpdk with the ENA, bonding and ring
# drivers; Ubuntu's dpdk package (21.11 on jammy) only provides the tools (dpdk-devbind).
set -euo pipefail
MAC="$1"; IP="$2"; CIDR="$3"; CPU="$4"
FSTACK_DIR=/opt/fstack
SRC=/opt/fstack-src
FSTACK_REF="${FSTACK_REF:-dev}"
DPDK_PREFIX=/opt/dpdk
export DEBIAN_FRONTEND=noninteractive

sudo apt-get update -qq
sudo apt-get install -y -qq dpdk dpdk-dev build-essential pkg-config git python3 python3-pyelftools \
    libnuma-dev libssl-dev libpcap-dev gawk meson ninja-build >/dev/null
echo "dpdk $(pkg-config --modversion libdpdk), kernel $(uname -r)"
sudo mkdir -p $FSTACK_DIR && sudo chown "$USER" $FSTACK_DIR

# ---------------------------------------------------------------------------
# F-Stack library
# ---------------------------------------------------------------------------
build_fstack() {   # $1: FF_DPDK (prefix whose lib/pkgconfig has libdpdk.pc)
    local dpdk="$1"
    export PKG_CONFIG_PATH="$dpdk/lib/x86_64-linux-gnu/pkgconfig:$dpdk/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
    # -fPIC: the library goes into the shim's .so. Its thread-locals (FreeBSD's curthread)
    # use the initial-exec TLS model, which a dlopen'd library may use while the static TLS
    # surplus lasts, instead of global-dynamic's __tls_get_addr on every access.
    # GCC 11 reports a false array-bounds positive in freebsd/kern/sys_generic.c, fatal under
    # F-Stack's -Werror.
    (cd $SRC/lib && make clean >/dev/null 2>&1 || true; make -j"$(nproc)" FF_DPDK="$dpdk" \
        CC="cc -fPIC -ftls-model=initial-exec -Wno-error=array-bounds" >/tmp/fstack-build.log 2>&1)
}
if [ ! -f $FSTACK_DIR/libfstack.a ]; then
    [ -d $SRC ] || sudo git clone -q --depth 1 --branch "$FSTACK_REF" https://github.com/F-Stack/f-stack.git $SRC
    sudo chown -R "$USER" $SRC
    echo "F-Stack $(git -C $SRC describe --always --tags) at $SRC, bundled DPDK $(cat $SRC/dpdk/VERSION)"
    if [ ! -f $DPDK_PREFIX/lib/x86_64-linux-gnu/pkgconfig/libdpdk.pc ] \
       || [ "$(cat $DPDK_PREFIX/VERSION 2>/dev/null)" != "$(cat $SRC/dpdk/VERSION)" ]; then
        echo "building DPDK $(cat $SRC/dpdk/VERSION) into $DPDK_PREFIX (ENA, bonding, ring)"
        # no kernel modules: igb_uio does not build on this kernel and vfio-pci is used anyway
        [ -f $SRC/dpdk/build/build.ninja ] || rm -rf $SRC/dpdk/build
        (cd $SRC/dpdk && meson setup $([ -f build/build.ninja ] && echo --reconfigure) -Dprefix=$DPDK_PREFIX -Dexamples= -Dtests=false -Denable_kmods=false \
            -Denable_drivers=net/ena,net/bonding,net/ring,net/tap,net/virtio,bus/pci,bus/vdev,mempool/ring,mempool/stack \
            build >/tmp/dpdk-meson.log 2>&1 && ninja -C build >/tmp/dpdk-build.log 2>&1 \
            && sudo ninja -C build install >/tmp/dpdk-install.log 2>&1)
        sudo cp $SRC/dpdk/VERSION $DPDK_PREFIX/VERSION
        sudo ldconfig $DPDK_PREFIX/lib/x86_64-linux-gnu
    fi
    if ! build_fstack $DPDK_PREFIX; then
        echo "F-Stack build failed; last lines of /tmp/fstack-build.log:"
        tail -20 /tmp/fstack-build.log
        exit 1
    fi
    FF_DPDK=$DPDK_PREFIX
    cp $SRC/lib/libfstack.a $FSTACK_DIR/
    cp $SRC/lib/ff_api.h $SRC/lib/ff_epoll.h $SRC/lib/ff_config.h $SRC/lib/ff_event.h $SRC/lib/ff_errno.h $FSTACK_DIR/ 2>/dev/null || true
    echo "$FF_DPDK" > $FSTACK_DIR/FF_DPDK
fi
FF_DPDK=$(cat $FSTACK_DIR/FF_DPDK)
export PKG_CONFIG_PATH="$FF_DPDK/lib/x86_64-linux-gnu/pkgconfig:$FF_DPDK/lib/pkgconfig:${PKG_CONFIG_PATH:-}"

# ---------------------------------------------------------------------------
# The shim the JVM loads (FFM): every ob_* call maps to one ff_* call
# ---------------------------------------------------------------------------
# The DPDK shared libraries are under $FF_DPDK, not on the loader's default path: the shim
# carries an rpath and the directory is registered with ldconfig as well
DPDK_LIBDIR=$FF_DPDK/lib/x86_64-linux-gnu
gcc -O2 -fPIC -shared -o $FSTACK_DIR/libomnibridge-fstack.so ~/dpdk/omnibridge_fstack.c \
    -I$FSTACK_DIR -I$SRC/lib $(pkg-config --cflags libdpdk) \
    -Wl,--whole-archive $FSTACK_DIR/libfstack.a -Wl,--no-whole-archive \
    $(pkg-config --libs libdpdk) -lrt -lm -ldl -lcrypto -lpthread -lnuma \
    -Wl,-rpath,$DPDK_LIBDIR
echo "$DPDK_LIBDIR" | sudo tee /etc/ld.so.conf.d/omnibridge-dpdk.conf >/dev/null && sudo ldconfig
ldd $FSTACK_DIR/libomnibridge-fstack.so | grep -q "not found" && { echo "shim has unresolved dependencies:"; ldd $FSTACK_DIR/libomnibridge-fstack.so | grep "not found"; exit 1; }
echo "shim built: $(ls -la $FSTACK_DIR/libomnibridge-fstack.so | awk '{print $5}') bytes"
nm -D $FSTACK_DIR/libomnibridge-fstack.so | grep -c ' T ob_' | xargs echo "exported ob_ symbols:"

# ---------------------------------------------------------------------------
# fstack.ini for the second ENI: this host's address on it, the subnet's mask,
# broadcast and gateway (AWS: the subnet's first address), one lcore
# ---------------------------------------------------------------------------
read -r NETMASK BROADCAST GATEWAY < <(python3 - "$IP" "$CIDR" <<'PY'
import ipaddress, sys
net = ipaddress.ip_network(sys.argv[2], strict=False)
print(net.netmask, net.broadcast_address, net.network_address + 1)
PY
)
LCORE_MASK=$(printf '%x' $((1 << CPU)))
cat > $FSTACK_DIR/fstack.ini <<INI
# Written by setup-fstack-host.sh for the second ENI ($MAC) on $(hostname)
[dpdk]
# One lcore: the engine's event-loop core (network.cpu-affinity = $CPU)
lcore_mask=$LCORE_MASK
channel=4
promiscuous=0
numa_on=1
tso=0
vlan_strip=1
# Never sleep and never batch: the loop busy-polls and every packet goes out at once
idle_sleep=0
pkt_tx_delay=0
symmetric_rss=0
port_list=0
nb_vdev=0
nb_bond=0

[pcap]
enable=0
snaplen=96
savelen=16777216
savepath=.

[port0]
addr=$IP
netmask=$NETMASK
broadcast=$BROADCAST
gateway=$GATEWAY

[freebsd.boot]
hz=1000
fd_reserve=1024
kern.ipc.maxsockets=262144
net.inet.tcp.syncache.hashsize=4096
net.inet.tcp.syncache.bucketlimit=100
net.inet.tcp.tcbhashsize=65536
kern.ncallout=262144
kern.features.inet6=0
net.inet6.ip6.auto_linklocal=0
net.inet6.ip6.accept_rtadv=0

[freebsd.sysctl]
kern.ipc.somaxconn=32768
kern.ipc.maxsockbuf=16777216
net.link.ether.inet.max_age=1200
net.inet.tcp.rfc1323=1
net.inet.tcp.fast_finwait2_recycle=1
net.inet.tcp.sendspace=32768
net.inet.tcp.recvspace=262144
net.inet.tcp.cc.algorithm=cubic
net.inet.tcp.sendbuf_max=16777216
net.inet.tcp.recvbuf_max=16777216
net.inet.tcp.sendbuf_auto=1
net.inet.tcp.recvbuf_auto=1
net.inet.tcp.sendbuf_inc=16384
net.inet.tcp.sack.enable=1
net.inet.tcp.blackhole=1
net.inet.tcp.msl=2000
# No delayed ACKs: an ack per segment, so the peer's send clock never waits
net.inet.tcp.delayed_ack=0
net.inet.udp.blackhole=1
net.inet.ip.redirect=0
net.inet.ip.forwarding=0
INI
echo "fstack.ini: addr $IP/$NETMASK gw $GATEWAY lcore_mask 0x$LCORE_MASK"

# ---------------------------------------------------------------------------
# hugepages and the NIC binding (same as the DPDK floor test)
# ---------------------------------------------------------------------------
echo 1024 | sudo tee /proc/sys/vm/nr_hugepages >/dev/null
grep -q hugetlbfs /proc/mounts || sudo mount -t hugetlbfs nodev /dev/hugepages
grep HugePages_Total /proc/meminfo
sudo modprobe vfio enable_unsafe_noiommu_mode=1 || true
sudo modprobe vfio-pci
echo 1 | sudo tee /sys/module/vfio/parameters/enable_unsafe_noiommu_mode >/dev/null
IFACE=""
for d in /sys/class/net/*; do
    if [ "$(cat "$d/address" 2>/dev/null)" = "$MAC" ]; then IFACE=$(basename "$d"); fi
done
if [ -n "$IFACE" ]; then
    PCI=$(basename "$(readlink "/sys/class/net/$IFACE/device")")
    echo "second ENI is $IFACE at $PCI"
    sudo ip link set dev "$IFACE" down
    sudo dpdk-devbind.py -b vfio-pci "$PCI"
else
    PCI=$(dpdk-devbind.py --status-dev net | grep 'drv=vfio-pci' | awk '{print $1}' | head -1)
fi
dpdk-devbind.py --status-dev net | grep -i "vfio\|ena" | head -5
# The engines run as root-less services: let them open the device and hugepages
sudo chmod a+rw /dev/vfio/* 2>/dev/null || true
sudo chmod 1777 /dev/hugepages
echo "PCI $PCI"
