#!/bin/bash
# =============================================================================
# Latency Host Check (runs on a trading host)
# =============================================================================
# Copied to /tmp and started in the background by run-aws-latency-test.sh.
# Finds the engine's event-loop thread (named NetworkIO-<loop name>) and
# records how the host treats it: once, the CPU isolation and IRQ setup; then
# every second, the core it ran on, its CPU time, context switches, interrupts
# on its core and other runnable threads there. run-aws-latency-test.sh turns
# the samples of the measured phase into PASS/WARN/FAIL checks.
#
# Usage: latency-host-check.sh <core|-1> <pid-spec> <max-secs> [run-log]
#   pid-spec  service:<unit> (systemd MainPID) or a pgrep -f pattern for a java process
#   run-log   latency run output; "Phase 2" and "Latency Statistics" in it are
#             printed as "mark start|end <epoch>"
#
# Output lines:
#   static <key> <value...>
#   sample <epoch> <psr> <utime+stime ticks> <vol cs> <invol cs> <dev irqs> <LOC irqs> <other runnable>
#   mark start|end <epoch>
#   done | error <reason>
# Stops when the thread exits, after max-secs, or when /tmp/host-check.stop exists.
# =============================================================================

CORE="$1"
PIDSPEC="$2"
MAXSECS="$3"
RUNLOG="${4:-}"

find_pid() {
    case "$PIDSPEC" in
        service:*) systemctl show -p MainPID --value "${PIDSPEC#service:}" | grep -v '^0$' ;;
        # The pattern also matches the ssh shell and timeout wrapping java
        *) for p in $(pgrep -f "$PIDSPEC"); do
               [ "$(cat /proc/$p/comm 2>/dev/null)" = java ] && echo "$p" && break
           done ;;
    esac
}

PID=""; TID=""
DEADLINE=$(( $(date +%s) + 180 ))
while [ "$(date +%s)" -lt "$DEADLINE" ] && [ ! -e /tmp/host-check.stop ]; do
    PID=$(find_pid)
    if [ -n "$PID" ]; then
        TID=$(grep -l '^NetworkIO-' /proc/$PID/task/*/comm 2>/dev/null | head -1 | cut -d/ -f5)
        [ -n "$TID" ] && break
    fi
    sleep 1
done
if [ -z "$TID" ]; then
    echo "error no NetworkIO- thread found for $PIDSPEC"
    exit 1
fi

echo "static pid $PID"
echo "static tid $TID"
echo "static comm $(cat /proc/$PID/task/$TID/comm)"
echo "static networkio_threads $(grep -l '^NetworkIO-' /proc/$PID/task/*/comm 2>/dev/null | wc -l)"
echo "static allowed $(taskset -pc "$TID" 2>/dev/null | awk -F': ' '{print $2}')"
echo "static cpus $(nproc --all)"
echo "static isolated $(cat /sys/devices/system/cpu/isolated 2>/dev/null)"
echo "static nohz_full $(grep -v '(null)' /sys/devices/system/cpu/nohz_full 2>/dev/null)"
echo "static cmdline $(grep -oE '(isolcpus|nohz_full|rcu_nocbs|irqaffinity)=[^ ]*' /proc/cmdline | tr '\n' ' ')"
echo "static irqbalance $(systemctl is-active irqbalance 2>/dev/null)"
if [ "$CORE" -ge 0 ]; then
    echo "static siblings $(cat /sys/devices/system/cpu/cpu$CORE/topology/thread_siblings_list 2>/dev/null)"
fi
# NIC queues (ENA on Nitro): which cores may take their interrupts
for irq in $(awk '/ena|eth[0-9]|ens[0-9]/ {sub(":", "", $1); print $1}' /proc/interrupts); do
    echo "static nic_irq $irq $(cat /proc/irq/$irq/smp_affinity_list 2>/dev/null)"
done
echo "static clk_tck $(getconf CLK_TCK)"

# Device IRQs and local timer interrupts taken by the measured core
irqs_on_core() {
    local core="$1"
    awk -v c=$((core + 2)) 'NR > 1 && $1 ~ /^[0-9]+:$/ { d += $c } $1 == "LOC:" { l = $c }
                            END { print d + 0, l + 0 }' /proc/interrupts
}

MARK_START=false; MARK_END=false
END=$(( $(date +%s) + MAXSECS ))
while [ -r /proc/$PID/task/$TID/stat ] && [ "$(date +%s)" -lt "$END" ] && [ ! -e /tmp/host-check.stop ]; do
    NOW=$(date +%s)
    # Fields after "pid (comm) ": $12 utime, $13 stime, $37 processor
    read -r PSR TICKS < <(sed 's/^.*) //' /proc/$PID/task/$TID/stat | awk '{print $37, $12 + $13}')
    VCS=$(awk '/^voluntary_ctxt_switches/ {print $2}' /proc/$PID/task/$TID/status)
    NVCS=$(awk '/^nonvoluntary_ctxt_switches/ {print $2}' /proc/$PID/task/$TID/status)
    C=$CORE; [ "$C" -ge 0 ] || C=$PSR
    read -r DIRQ LOC < <(irqs_on_core "$C")
    OTHERS=$(ps -eLo psr=,stat=,tid=,comm= | awk -v c="$C" -v t="$TID" \
             '$1 == c && $2 ~ /^R/ && $3 != t && $4 != "ps" && $4 != "awk"' | wc -l)
    [ -n "$PSR" ] && echo "sample $NOW $PSR $TICKS $VCS $NVCS $DIRQ $LOC $OTHERS"
    if [ -n "$RUNLOG" ] && [ -r "$RUNLOG" ]; then
        if [ "$MARK_START" = false ] && grep -q 'Phase 2: Latency test' "$RUNLOG"; then
            echo "mark start $NOW"; MARK_START=true
        fi
        if [ "$MARK_END" = false ] && grep -qE 'Latency Statistics|TEST ABORTED' "$RUNLOG"; then
            echo "mark end $NOW"; MARK_END=true
        fi
    fi
    sleep 1
done
echo "done"
