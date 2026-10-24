#!/bin/bash
# =============================================================================
# Latency Host Tuning (runs on a trading host, as root)
# =============================================================================
# Keeps the engine's event-loop core to itself: the core and its hyperthread
# sibling(s) are isolated at boot (isolcpus, nohz_full, rcu_nocbs), every IRQ
# is steered to the other cores (irqaffinity, plus a boot-time unit that moves
# the IRQs already registered, such as the ENA queues) and irqbalance is
# stopped so it cannot move them back. The pinned event loop is the only task
# on the isolated core, so it is neither preempted nor interrupted.
#
# The trading hosts' user data runs "apply" at first boot and reboots;
# run-aws-latency-test.sh's host checks report the result (--require-isolation
# fails the run without it). "remove" undoes it, for a before/after on one host.
#
# Usage: latency-host-tuning.sh apply <cpu> [extra-cpus] | remove | status
#   apply   isolate <cpu> and its siblings, plus extra-cpus (e.g. "2" for the
#           latency initiator's pinned sending thread); takes effect after a reboot
#   remove  restore the default kernel command line and irqbalance; reboot after
#   status  print the configured and the running state
# =============================================================================

set -euo pipefail

GRUB_CFG=/etc/default/grub.d/99-omnibridge-isolation.cfg
IRQ_UNIT=/etc/systemd/system/omnibridge-irq-affinity.service

# Expands "1,3" or "0-1" to one number per line
expand_list() {
    tr ',' '\n' <<< "$1" | while IFS=- read -r a b; do
        if [ -n "$a" ]; then seq "$a" "${b:-$a}"; fi
    done
}

case "${1:-}" in
    apply)
        CPU="${2:?usage: $0 apply <cpu> [extra-cpus]}"
        EXTRA="${3:-}"
        NCPU=$(nproc --all)
        if [ "$CPU" -le 0 ] || [ "$CPU" -ge "$NCPU" ]; then
            echo "ERROR: cpu $CPU outside 1..$((NCPU - 1)) (cpu 0 stays for housekeeping)"
            exit 1
        fi
        for c in $(expand_list "$EXTRA"); do
            if [ "$c" -le 0 ] || [ "$c" -ge "$NCPU" ]; then
                echo "ERROR: extra cpu $c outside 1..$((NCPU - 1))"
                exit 1
            fi
        done
        SIBLINGS=$(cat "/sys/devices/system/cpu/cpu$CPU/topology/thread_siblings_list")
        ISOLATED=$( (expand_list "$SIBLINGS"; expand_list "$EXTRA") | sort -nu | paste -sd, -)
        HOUSEKEEPING=$(seq 0 $((NCPU - 1)) | grep -vxF -f <(tr ',' '
' <<< "$ISOLATED") | paste -sd, -)
        if [ -z "$HOUSEKEEPING" ]; then
            echo "ERROR: isolating $ISOLATED would leave no housekeeping cpu"
            exit 1
        fi

        # Ubuntu cloud images read /etc/default/grub.d/*.cfg in order; this one appends
        cat > "$GRUB_CFG" << EOF
# Written by latency-host-tuning.sh: event-loop cpu $CPU, its siblings and ${EXTRA:-no} extra cpus isolated ($ISOLATED)
GRUB_CMDLINE_LINUX_DEFAULT="\$GRUB_CMDLINE_LINUX_DEFAULT isolcpus=managed_irq,domain,$ISOLATED nohz_full=$ISOLATED rcu_nocbs=$ISOLATED irqaffinity=$HOUSEKEEPING"
EOF
        update-grub 2>&1 | tail -1

        # irqaffinity= only sets the default; IRQs the drivers registered get moved at boot
        MASK=0
        for c in $(expand_list "$HOUSEKEEPING"); do MASK=$((MASK | (1 << c))); done
        cat > "$IRQ_UNIT" << EOF
[Unit]
Description=OmniBridge: IRQs off the isolated event-loop cpus ($ISOLATED)
After=network-online.target

[Service]
Type=oneshot
ExecStart=/bin/sh -c 'echo $(printf %x $MASK) > /proc/irq/default_smp_affinity; for f in /proc/irq/*/smp_affinity_list; do echo $HOUSEKEEPING > \$\$f 2>/dev/null || true; done'

[Install]
WantedBy=multi-user.target
EOF
        systemctl daemon-reload
        systemctl enable omnibridge-irq-affinity.service
        systemctl disable --now irqbalance 2>/dev/null || true
        echo "Isolated cpus $ISOLATED, housekeeping $HOUSEKEEPING; reboot to apply"
        ;;
    remove)
        rm -f "$GRUB_CFG"
        update-grub 2>&1 | tail -1
        systemctl disable omnibridge-irq-affinity.service 2>/dev/null || true
        rm -f "$IRQ_UNIT"
        systemctl daemon-reload
        systemctl enable --now irqbalance 2>/dev/null || true
        echo "Isolation removed; reboot to apply"
        ;;
    status)
        echo "configured: $(grep -o 'isolcpus=[^ ]*' "$GRUB_CFG" 2>/dev/null || echo none)"
        echo "running:    isolated=$(cat /sys/devices/system/cpu/isolated) nohz_full=$(cat /sys/devices/system/cpu/nohz_full)"
        echo "irqbalance: $(systemctl is-active irqbalance 2>/dev/null || true)"
        ;;
    *)
        sed -n '2,21p' "$0"
        exit 1
        ;;
esac
