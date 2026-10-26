#!/bin/bash
# Runs on a trading host: installs DPDK and sockperf from Ubuntu's packages,
# reserves hugepages, builds pingpong, and binds the ENI whose MAC is $1 to
# the kernel's vfio-pci driver in no-IOMMU mode. Prints "PCI <addr>" at the end.
set -euo pipefail
MAC="$1"
export DEBIAN_FRONTEND=noninteractive
sudo apt-get update -qq
sudo apt-get install -y -qq dpdk dpdk-dev build-essential pkg-config sockperf >/dev/null
echo "dpdk $(pkg-config --modversion libdpdk), kernel $(uname -r)"
ls /usr/lib/x86_64-linux-gnu/dpdk/pmds-*/librte_net_ena.so* >/dev/null && echo "ena pmd present"

# hugepages: 1024 x 2 MB
echo 1024 | sudo tee /proc/sys/vm/nr_hugepages >/dev/null
grep -q hugetlbfs /proc/mounts || sudo mount -t hugetlbfs nodev /dev/hugepages
grep HugePages_Total /proc/meminfo

# build pingpong
cd ~/dpdk
gcc -O2 -o pingpong pingpong.c $(pkg-config --cflags --libs libdpdk)
echo "pingpong built"

# vfio-pci without an IOMMU (EC2 virtual machines expose none)
sudo modprobe vfio enable_unsafe_noiommu_mode=1 || true
sudo modprobe vfio-pci
echo 1 | sudo tee /sys/module/vfio/parameters/enable_unsafe_noiommu_mode >/dev/null
cat /sys/module/vfio/parameters/enable_unsafe_noiommu_mode

# find the interface with that MAC and bind it
IFACE=""
for d in /sys/class/net/*; do
    if [ "$(cat "$d/address" 2>/dev/null)" = "$MAC" ]; then IFACE=$(basename "$d"); fi
done
if [ -z "$IFACE" ]; then
    PCI=$(dpdk-devbind.py --status-dev net | grep 'drv=vfio-pci' | awk '{print $1}' | head -1)
else
    PCI=$(basename "$(readlink "/sys/class/net/$IFACE/device")")
    echo "second ENI is $IFACE at $PCI"
    sudo ip link set dev "$IFACE" down
    sudo dpdk-devbind.py -b vfio-pci "$PCI"
fi
dpdk-devbind.py --status-dev net
echo "PCI $PCI"
