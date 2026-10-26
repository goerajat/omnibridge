#!/bin/bash
# =============================================================================
# AWS Latency Test
# =============================================================================
# Measures the FIX round trip between the deployed FIX initiator (OUCH host) and
# the exchange simulator (FIX host), next to the raw TCP round trip between the
# same two hosts measured with sockperf.
#
# Steps:
#   1. Stop the fix-initiator service (an EXIT trap always restores it).
#   2. Baseline: run sockperf sr on the FIX host and sockperf pp from the OUCH
#      host, on port 11111 (opened between the trading hosts by Terraform).
#      On a stack without that rule, --sockperf-port 9876 borrows the FIX port
#      and stops the simulator for the duration.
#   3. Restart the simulator with busy-spin, cpu-affinity, demo mode off and
#      FIX-SIM-1's message settings, like the initiator (its exchange-simulator.conf is
#      backed up and restored afterwards; --no-tune-simulator leaves it as deployed).
#   4. Run SampleInitiator --latency with a tuned override conf (busy-spin,
#      cpu-affinity, log-messages = false, reset-on-logon = true).
#   5. Realign FIX-SIM-1's sequence numbers through the simulator admin API so
#      the restarted demo initiator does not disconnect, then restart it.
#
# During step 4, latency-host-check.sh samples the event-loop thread on both
# hosts. For the measured phase the summary reports whether the thread stayed
# on its configured core, busy-spin CPU use, whether the core and its
# hyperthread sibling are isolated, NIC IRQ placement, and preemptions.
#
# Usage: ./run-aws-latency-test.sh [options]
#
# Options:
#   -f, --tf-outputs      Terraform output JSON file (default: tf-outputs.json)
#   -i, --identity        PEM file (default: omnibridge-key.pem)
#   -u, --user            SSH username (default: ubuntu)
#   --ssh-port            SSH port (default: 22)
#   -w, --warmup          Warmup orders (default: 10000)
#   -n, --test-orders     Measured orders (default: 1000)
#   -r, --rate            Orders per second (default: 100)
#   --initiator-cpu       Initiator event loop core (default: 3)
#   --no-tune-simulator   Leave the simulator as deployed (default: busy-spin,
#                         pinned to --simulator-cpu, demo order tracking off)
#   --simulator-cpu       Simulator event loop core (default: 3)
#   --skip-baseline       Skip the sockperf measurement
#   --sockperf-time       sockperf duration in seconds (default: 30)
#   --sockperf-size       sockperf message size in bytes (default: 200)
#   --sockperf-port       sockperf TCP port (default: 11111)
#   --outgoing-seq        Simulator outgoing seq set after the run (default: 1000000)
#   -o, --output-dir      Local results directory (default: logs/aws-latency/<time>)
#   --history             JSONL file each run's summary.json is appended to
#                         (default: docs/latency-history.jsonl; "" to skip)
#   --no-host-check       Skip the affinity/isolation sampling
#   --require-isolation   Fail unless the pinned cores are isolated, clear of
#                         NIC IRQs and not preempted (default: only warn)
#   --stages              Per-stage engine timestamps on both sides
#                         (fix-engine.stage-timing.enabled); the initiator prints its
#                         report, the simulator's is taken from its journal into
#                         simulator-stages.txt. Needs the simulator tuning. Costs a
#                         few us per message: keep it off for headline numbers.
#   --no-persistence      Measured sessions persist nothing (log-messages = false).
#                         By default both sides persist every message to the
#                         session store (log-messages = true) without tracing
#                         it to the application log (trace-messages = false).
#                         --persistence is accepted for older commands.
#   --nic-tuning          On both trading hosts, for the whole run (sockperf too):
#                         ENA interrupt moderation off (ethtool -C adaptive-rx off
#                         rx-usecs 0) and kernel busy polling on
#                         (net.core.busy_read = busy_poll = 50). Restored afterwards.
#                         Note: busy polling also speeds up sockperf's blocking
#                         receive, so FIX - TCP is not engine time in such a run.
#   --no-pretouch         Chronicle pretouch off on both sides (persistence.pretouch-ahead
#                         = 0): the writer takes its own page faults, as before 4428f77.
#                         Recorded as options.pretouch.
#   --sender              Thread the initiator sends the orders from: loop (the
#                         event loop's own thread, the default) or main (the
#                         calling thread, which spins on the schedule and commits
#                         to the ring). Recorded as options.sender.
#   --transport           kernel (the default: NIO sockets on the primary ENI) or
#                         fstack: both engines run FreeBSD TCP on DPDK (F-Stack,
#                         network.transport = fstack) over the second ENI of each
#                         host; the measured session connects to the simulator's
#                         second-ENI address. Needs run-dpdk-floor.sh enis + fstack
#                         (logs/dpdk-floor/state.env, /opt/fstack on both hosts) and
#                         the simulator tuning. sockperf stays on the kernel path.
#                         Recorded as options.transport (lines without it: kernel).
#
# Examples:
#   ./run-aws-latency-test.sh
#   ./run-aws-latency-test.sh -w 20000 -n 100000 -r 1000
#   ./run-aws-latency-test.sh --no-tune-simulator          # simulator as deployed
#   ./run-aws-latency-test.sh --nic-tuning                 # moderation off, busy polling on
#   ./run-aws-latency-test.sh --sender main                # the older sender, for an A/B
#   ./run-aws-latency-test.sh --transport fstack           # F-Stack on both sides
# =============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Defaults
TF_OUTPUTS="$PROJECT_DIR/tf-outputs.json"
PEM_FILE="$PROJECT_DIR/omnibridge-key.pem"
SSH_USER="ubuntu"
SSH_PORT=22
WARMUP=10000
TEST_ORDERS=1000
RATE=100
INITIATOR_CPU=3
TUNE_SIM=true
SIM_CPU=3
BASELINE=true
SP_TIME=30
SP_SIZE=200
SP_PORT=11111
OUT_SEQ=1000000
OUT_DIR=""
HISTORY="$PROJECT_DIR/docs/latency-history.jsonl"
HOST_CHECK=true
REQUIRE_ISOLATION=false
PERSIST=true
STAGES=false
NIC_TUNING=false
SENDER=loop
PRETOUCH=true
TRANSPORT=kernel

while [[ $# -gt 0 ]]; do
    case "$1" in
        -f|--tf-outputs)  TF_OUTPUTS="$2";    shift 2 ;;
        -i|--identity)    PEM_FILE="$2";      shift 2 ;;
        -u|--user)        SSH_USER="$2";      shift 2 ;;
        --ssh-port)       SSH_PORT="$2";      shift 2 ;;
        -w|--warmup)      WARMUP="$2";        shift 2 ;;
        -n|--test-orders) TEST_ORDERS="$2";   shift 2 ;;
        -r|--rate)        RATE="$2";          shift 2 ;;
        --initiator-cpu)  INITIATOR_CPU="$2"; shift 2 ;;
        --tune-simulator) TUNE_SIM=true;      shift ;;  # the default; kept for old invocations
        --no-tune-simulator) TUNE_SIM=false;  shift ;;
        --simulator-cpu)  SIM_CPU="$2";       shift 2 ;;
        --skip-baseline)  BASELINE=false;     shift ;;
        --sockperf-time)  SP_TIME="$2";       shift 2 ;;
        --sockperf-size)  SP_SIZE="$2";       shift 2 ;;
        --sockperf-port)  SP_PORT="$2";       shift 2 ;;
        --outgoing-seq)   OUT_SEQ="$2";       shift 2 ;;
        -o|--output-dir)  OUT_DIR="$2";       shift 2 ;;
        --history)        HISTORY="$2";       shift 2 ;;
        --no-host-check)  HOST_CHECK=false;   shift ;;
        --require-isolation) REQUIRE_ISOLATION=true; shift ;;
        --persistence)    PERSIST=true;       shift ;;  # the default; kept for old invocations
        --no-persistence) PERSIST=false;      shift ;;
        --stages)         STAGES=true;        shift ;;
        --nic-tuning)     NIC_TUNING=true;    shift ;;
        --sender)         SENDER="$2";        shift 2 ;;
        --no-pretouch)    PRETOUCH=false;     shift ;;
        --transport)      TRANSPORT="$2";     shift 2 ;;
        -h|--help)        sed -n '2,80p' "$0"; exit 0 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# -----------------------------------------------------------------------------
# Prerequisites
# -----------------------------------------------------------------------------

case "$SENDER" in loop|main) ;; *) echo "Error: --sender must be loop or main"; exit 1 ;; esac
case "$TRANSPORT" in kernel|fstack) ;; *) echo "Error: --transport must be kernel or fstack"; exit 1 ;; esac
if [ "$STAGES" = true ] && [ "$TUNE_SIM" = false ]; then
    echo "Error: --stages needs the simulator tuning (drop --no-tune-simulator)"
    exit 1
fi
# F-Stack: both engines on the second ENIs (run-dpdk-floor.sh enis + fstack), simulator
# re-configured with the tuning block
FSTACK_HOCON=""
SESSION_EXTRA=""
FIX2=""; OUCH2=""
if [ "$TRANSPORT" = fstack ]; then
    if [ "$TUNE_SIM" = false ]; then
        echo "Error: --transport fstack needs the simulator tuning (drop --no-tune-simulator)"
        exit 1
    fi
    FSTACK_STATE="$PROJECT_DIR/logs/dpdk-floor/state.env"
    if [ ! -f "$FSTACK_STATE" ]; then
        echo "Error: --transport fstack needs $FSTACK_STATE (run scripts/deploy/dpdk/run-dpdk-floor.sh enis, then fstack)"
        exit 1
    fi
    # shellcheck disable=SC1090
    source "$FSTACK_STATE"
    if [ -z "${FIX2:-}" ] || [ -z "${OUCH2:-}" ]; then
        echo "Error: $FSTACK_STATE has no FIX2/OUCH2 addresses"
        exit 1
    fi
    FSTACK_HOCON=$'    transport = fstack\n    fstack {\n        conf = "/opt/fstack/fstack.ini"\n        library = "/opt/fstack/libomnibridge-fstack.so"\n    }'
    # the measured session connects to the simulator's second ENI (last host wins in HOCON)
    SESSION_EXTRA='\n\1host = "'"$FIX2"'"'
fi

if [ ! -f "$TF_OUTPUTS" ]; then
    echo "Error: Terraform output file not found: $TF_OUTPUTS"
    exit 1
fi
if [ ! -f "$PEM_FILE" ]; then
    echo "Error: PEM file not found: $PEM_FILE"
    exit 1
fi
if ! command -v jq &>/dev/null; then
    echo "Error: jq is required (Windows: winget install jqlang.jq; Linux: apt install jq)"
    exit 1
fi

PEM_FILE="$(cd "$(dirname "$PEM_FILE")" && pwd)/$(basename "$PEM_FILE")"

# jq reads from stdin: a native Windows jq cannot open Cygwin (/cygdrive/...) paths
out() { jq -r ".$1.value // empty" < "$TF_OUTPUTS" | tr -d '\r'; }

MONITORING_IP=$(out monitoring_public_ip)
FIX_IP=$(out fix_acceptor_private_ip)
OUCH_IP=$(out ouch_acceptor_private_ip)

if [ -z "$MONITORING_IP" ] || [ -z "$FIX_IP" ] || [ -z "$OUCH_IP" ]; then
    echo "Error: $TF_OUTPUTS is missing host IPs; re-run terraform-deploy.sh"
    exit 1
fi

[ -n "$OUT_DIR" ] || OUT_DIR="$PROJECT_DIR/logs/aws-latency/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT_DIR"

SSH_OPTS="-i $PEM_FILE -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=10 -o BatchMode=yes -p $SSH_PORT"
PROXY_CMD="ssh -i $PEM_FILE -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -p $SSH_PORT -W %h:%p $SSH_USER@$MONITORING_IP"

# Both trading hosts are private and reached through the monitoring host
run_on() {
    local host="$1"; shift
    ssh $SSH_OPTS -o "ProxyCommand=$PROXY_CMD" "$SSH_USER@$host" "$@"
}
on_fix()  { run_on "$FIX_IP"  "$@"; }
on_ouch() { run_on "$OUCH_IP" "$@"; }

SIM_DIR=/opt/exchange-simulator
INIT_DIR=/opt/fix-initiator
SIM_CONF=$SIM_DIR/exchange-simulator.conf
SIM_BACKUP=$SIM_CONF.latency-backup
OVERRIDE=$INIT_DIR/conf/initiator-latency.conf
SESSION_URL='http://localhost:8080/api/sessions/EXCH1-%3ECLIENT1'
# --transport fstack: vfio in no-IOMMU mode needs CAP_SYS_RAWIO, so the simulator service
# runs as root through this drop-in for the run, and the initiator JVM under sudo
SIM_DROPIN=/etc/systemd/system/exchange-simulator.service.d/latency-fstack.conf
SUDO=""
if [ "$TRANSPORT" = fstack ]; then
    SUDO="sudo "
    for h in fix ouch; do
        if ! on_$h "test -f /opt/fstack/libomnibridge-fstack.so -a -f /opt/fstack/fstack.ini"; then
            echo "Error: /opt/fstack is not set up on the $h host (run scripts/deploy/dpdk/run-dpdk-floor.sh fstack)"
            exit 1
        fi
    done
fi
# Session settings for the measured sessions: store writes on or off, never traced
if [ "$PERSIST" = true ]; then
    MSG_CFG='log-messages = true, trace-messages = false'
else
    MSG_CFG='log-messages = false'
fi

echo "================================================================"
echo "  AWS latency test"
echo "================================================================"
echo "  FIX host (simulator):   $FIX_IP"
echo "  OUCH host (initiator):  $OUCH_IP"
echo "  Orders:                 warmup $WARMUP, test $TEST_ORDERS at $RATE/s"
echo "  Initiator core:         $INITIATOR_CPU"
echo "  Sender:                 $SENDER"
echo "  Transport:              $([ "$TRANSPORT" = fstack ] && echo "fstack (simulator $FIX2, initiator $OUCH2 on the second ENIs)" || echo kernel)"
echo "  Chronicle pretouch:     $PRETOUCH"
echo "  Simulator tuning:       $([ "$TUNE_SIM" = true ] && echo "on, core $SIM_CPU" || echo off)"
echo "  Persistence:            $([ "$PERSIST" = true ] && echo "on (store writes, no tracing)" || echo off)"
echo "  Stage timing:           $STAGES"
echo "  NIC tuning:             $([ "$NIC_TUNING" = true ] && echo "on (adaptive-rx off, rx-usecs 0, busy_read/busy_poll 50)" || echo off)"
echo "  Baseline (sockperf):    $([ "$BASELINE" = true ] && echo "${SP_SIZE}B for ${SP_TIME}s on port $SP_PORT" || echo skipped)"
echo "  Results:                $OUT_DIR"
echo ""

# -----------------------------------------------------------------------------
# Cleanup: always put both services back the way the demo expects them
# -----------------------------------------------------------------------------

INITIATOR_STOPPED=false
SIM_STOPPED=false
SIM_TUNED=false
NIC_TUNED=false
CLEANED=false

# --nic-tuning: the settings are changed live on each trading host and the
# values found there are kept in /tmp for the restore. The interface is the one
# carrying the default route (ens5 on these hosts).
NIC_APPLY=$(cat <<'REMOTE'
set -e
IFACE=$(ip -o route get 1.1.1.1 | sed -n 's/.* dev \([^ ]*\).*/\1/p')
[ -n "$IFACE" ] || { echo "  no default-route interface"; exit 1; }
{
    echo "iface=$IFACE"
    echo "adaptive_rx=$(ethtool -c "$IFACE" | awk '/^Adaptive RX:/ { print tolower($3) }')"
    echo "rx_usecs=$(ethtool -c "$IFACE" | awk '/^rx-usecs:/ { print $2 }')"
    echo "busy_read=$(sysctl -n net.core.busy_read)"
    echo "busy_poll=$(sysctl -n net.core.busy_poll)"
} > /tmp/latency-nic-before
sudo ethtool -C "$IFACE" adaptive-rx off
sudo ethtool -C "$IFACE" rx-usecs 0
sudo sysctl -q -w net.core.busy_read=50 net.core.busy_poll=50
echo "  $(hostname): $IFACE $(ethtool -c "$IFACE" | awk '/^Adaptive RX:/ { printf "adaptive-rx %s", tolower($3) } /^rx-usecs:/ { printf ", rx-usecs %s", $2 }'), busy_read $(sysctl -n net.core.busy_read), busy_poll $(sysctl -n net.core.busy_poll) (was: $(tr '\n' ' ' < /tmp/latency-nic-before))"
REMOTE
)
NIC_RESTORE=$(cat <<'REMOTE'
[ -f /tmp/latency-nic-before ] || exit 0
. /tmp/latency-nic-before
# ENA rejects rx-usecs while adaptive RX is on, so set the fixed value first
sudo ethtool -C "$iface" rx-usecs "$rx_usecs" 2>/dev/null
sudo ethtool -C "$iface" adaptive-rx "$adaptive_rx"
sudo sysctl -q -w net.core.busy_read="$busy_read" net.core.busy_poll="$busy_poll"
rm -f /tmp/latency-nic-before
echo "  $(hostname): $iface adaptive-rx $(ethtool -c "$iface" | awk '/^Adaptive RX:/ { print tolower($3) }'), busy_read $(sysctl -n net.core.busy_read)"
REMOTE
)
nic_apply()   { run_on "$1" "bash -s" <<<"$NIC_APPLY"; }
nic_restore() { run_on "$1" "bash -s" <<<"$NIC_RESTORE"; }

# Waits until FIX-SIM-1 is known to the admin API and the FIX port listens
wait_for_simulator() {
    on_fix "bash -s" <<REMOTE
for i in \$(seq 1 90); do
    code=\$(curl -s -o /dev/null -w '%{http_code}' '$SESSION_URL' 2>/dev/null)
    # an F-Stack listener is not in the kernel's socket table: the admin API has to do
    if [ "\$code" = 200 ] && { [ "$TRANSPORT" = fstack ] || ss -ltn 2>/dev/null | grep -q ':9876 '; }; then exit 0; fi
    sleep 2
done
echo "  simulator not ready after 180s"
exit 1
REMOTE
}

cleanup() {
    [ "$CLEANED" = true ] && return
    CLEANED=true
    trap - INT TERM
    echo ""
    echo "[cleanup] Restoring services"

    # A latency run cut short by Ctrl-C would keep its session open (root under --transport fstack)
    on_ouch "sudo pkill -f '[o]mnibridge.latency-test'; pkill -f '[l]atency-host-check'; true" 2>/dev/null
    on_fix "pkill -x sockperf; pkill -f '[l]atency-host-check'; true" 2>/dev/null
    if [ "$TRANSPORT" = fstack ]; then
        # the run's JVMs were root: hand their files back to the service user
        on_ouch "sudo chown -R $SSH_USER:$SSH_USER $INIT_DIR/data 2>/dev/null; true"
        on_fix "sudo rm -f $SIM_DROPIN; sudo systemctl daemon-reload; sudo chown -R $SSH_USER:$SSH_USER $SIM_DIR/data 2>/dev/null; true"
    fi

    if [ "$NIC_TUNED" = true ]; then
        echo "[cleanup] Restoring NIC settings"
        nic_restore "$FIX_IP"
        nic_restore "$OUCH_IP"
    fi
    if [ "$SIM_TUNED" = true ]; then
        echo "[cleanup] Restoring $SIM_CONF"
        on_fix "mv -f $SIM_BACKUP $SIM_CONF && sudo systemctl restart exchange-simulator"
        SIM_STOPPED=false
    fi
    if [ "$SIM_STOPPED" = true ]; then
        echo "[cleanup] Starting exchange-simulator"
        on_fix "sudo systemctl start exchange-simulator"
    fi

    if [ "$INITIATOR_STOPPED" = true ]; then
        echo "[cleanup] Waiting for the simulator"
        wait_for_simulator
        # The run reset both sides to 1. The demo initiator resumes from its own
        # store: expecting incoming 1 lets the simulator gap-fill the initiator's
        # higher numbers, and a high outgoing number is never "too low" for it.
        echo "[cleanup] Realigning FIX-SIM-1 sequence numbers (incoming 1, outgoing $OUT_SEQ)"
        on_fix "curl -s -X PUT -H 'Content-Type: application/json' \
            -d '{\"incomingSeqNum\":1,\"outgoingSeqNum\":$OUT_SEQ}' '$SESSION_URL/sequence'"
        echo ""
        echo "[cleanup] Starting fix-initiator"
        on_ouch "sudo systemctl start fix-initiator"
    fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# -----------------------------------------------------------------------------
# 1. Stop the demo initiator
# -----------------------------------------------------------------------------

echo "[1/4] Stopping fix-initiator on $OUCH_IP"
INITIATOR_STOPPED=true
if ! on_ouch "sudo systemctl stop fix-initiator"; then
    echo "  FAIL: could not stop fix-initiator"
    exit 1
fi

if [ "$NIC_TUNING" = true ]; then
    echo "[nic] Interrupt moderation off, busy polling on, both hosts"
    NIC_TUNED=true
    if ! nic_apply "$FIX_IP" || ! nic_apply "$OUCH_IP"; then
        echo "  FAIL: could not apply the NIC settings"
        exit 1
    fi
fi

# -----------------------------------------------------------------------------
# 2. Network floor: sockperf over the simulator's FIX port
# -----------------------------------------------------------------------------

SP_LOG="$OUT_DIR/sockperf.log"
if [ "$BASELINE" = true ]; then
    echo "[2/4] sockperf baseline"
    INSTALL='command -v sockperf >/dev/null || { sudo apt-get update -qq && sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq sockperf; } >/dev/null; command -v sockperf >/dev/null'
    if ! on_fix "$INSTALL" || ! on_ouch "$INSTALL"; then
        echo "  WARN: sockperf could not be installed; skipping baseline"
    else
        # The simulator's own ports are the only others open between the hosts
        if [ "$SP_PORT" = 9876 ] || [ "$SP_PORT" = 9200 ]; then
            SIM_STOPPED=true
            on_fix "sudo systemctl stop exchange-simulator"
        fi
        on_fix "nohup sockperf sr --tcp -i 0.0.0.0 -p $SP_PORT > /tmp/sockperf-sr.log 2>&1 < /dev/null & sleep 2; pgrep -x sockperf >/dev/null"
        # --full-rtt reports the round trip, comparable to the FIX numbers (default is half)
        on_ouch "sockperf pp --tcp -i $FIX_IP -p $SP_PORT -m $SP_SIZE -t $SP_TIME --full-rtt" 2>&1 | tee "$SP_LOG"
        on_fix "pkill -x sockperf || true"
    fi
else
    echo "[2/4] sockperf baseline skipped"
fi

# -----------------------------------------------------------------------------
# 3. Simulator: optional tuning, then make sure it is up
# -----------------------------------------------------------------------------

if [ "$TUNE_SIM" = true ]; then
    echo "[3/4] Tuning simulator (busy-spin, core $SIM_CPU, demo off, FIX-SIM-1 $MSG_CFG)"
    # The simulator loads only exchange-simulator.conf, so the tuning is appended
    # there. HOCON replaces arrays, so all five FIX sessions from the classpath
    # exchange-simulator.conf are restated. The deployed file is CRLF; HOCON
    # accepts the mixed line endings and the backup restores it byte for byte.
    on_fix "cp -p $SIM_CONF $SIM_BACKUP" || { echo "  FAIL: could not back up $SIM_CONF"; exit 1; }
    SIM_TUNED=true
    on_fix "cat >> $SIM_CONF" <<CONF

# --- run-aws-latency-test.sh tuning (restored from $SIM_BACKUP) ---
network {
    busy-spin-mode = true
    cpu-affinity = $SIM_CPU
$FSTACK_HOCON
}

$([ "$PRETOUCH" = true ] || printf 'persistence {\n    pretouch-ahead = 0\n}\n')
# The demo order tracker records every order (and its ClOrdID index in the order book)
# on the event loop; the deployed config turns it on for the demo UI
demo {
    enabled = false
}

fix-engine {
    stage-timing { enabled = $STAGES }
    sessions = [
        { session-id = "FIX-SIM-1", port = 9876, sender-comp-id = "EXCH1", target-comp-id = "CLIENT1", initiator = false, begin-string = "FIX.4.4", heartbeat-interval = 30, $MSG_CFG },
        { session-id = "FIX-SIM-2", port = 9876, sender-comp-id = "EXCH2", target-comp-id = "CLIENT2", initiator = false, begin-string = "FIX.4.4", heartbeat-interval = 30 },
        { session-id = "FIX-SIM-3", port = 9876, sender-comp-id = "EXCH3", target-comp-id = "CLIENT3", initiator = false, begin-string = "FIX.4.4", heartbeat-interval = 30 },
        { session-id = "FIX-SIM-4", port = 9876, sender-comp-id = "EXCH4", target-comp-id = "CLIENT4", initiator = false, begin-string = "FIX.4.4", heartbeat-interval = 30 },
        { session-id = "FIX-SIM-5", port = 9876, sender-comp-id = "EXCH5", target-comp-id = "CLIENT5", initiator = false, begin-string = "FIX.4.4", heartbeat-interval = 30 }
    ]
}
CONF
    if [ "$TRANSPORT" = fstack ]; then
        on_fix "sudo mkdir -p $(dirname $SIM_DROPIN) && printf '[Service]\nUser=root\nLimitMEMLOCK=infinity\n' | sudo tee $SIM_DROPIN >/dev/null && sudo systemctl daemon-reload"
    fi
    on_fix "sudo systemctl restart exchange-simulator"
    SIM_STOPPED=false
elif [ "$SIM_STOPPED" = true ]; then
    echo "[3/4] Starting exchange-simulator"
    on_fix "sudo systemctl start exchange-simulator"
    SIM_STOPPED=false
else
    echo "[3/4] Simulator left as deployed"
fi
if ! wait_for_simulator; then
    echo "  FAIL: simulator did not come up; see journalctl -u exchange-simulator"
    exit 1
fi

# -----------------------------------------------------------------------------
# 4. FIX latency run
# -----------------------------------------------------------------------------

echo "[4/4] FIX latency run"
# HOCON replaces arrays, so the session block of initiator-aws.conf is restated
# with the latency settings added after 'initiator = true'. The deployed file is
# CRLF, hence the tr. A separate persistence path keeps the demo's store intact.
if ! on_ouch "bash -s" <<REMOTE
set -e
{
    echo 'network {'
    echo '    busy-spin-mode = true'
    echo '    cpu-affinity = $INITIATOR_CPU'
    printf '%s\n' '$FSTACK_HOCON'
    echo '}'
    echo 'persistence {'
    echo '    base-path = "$INIT_DIR/data/latency-logs"'
    [ "$PRETOUCH" = true ] || echo '    pretouch-ahead = 0'
    echo '}'
    tr -d '\r' < $INIT_DIR/conf/initiator-aws.conf | sed -n '/^fix-engine {/,\$p' \
        | sed 's/^\( *\)initiator = true.*/&\n\1reset-on-logon = true\n\1$MSG_CFG$SESSION_EXTRA/'
} > $OVERRIDE
grep -q 'reset-on-logon = true' $OVERRIDE
sudo rm -rf $INIT_DIR/data/latency-logs
REMOTE
then
    echo "  FAIL: could not write $OVERRIDE from initiator-aws.conf"
    exit 1
fi

# The simulator logs its read-cap count at the initiator's Logout; its journal is read from here
SIM_RUN_START=$(on_fix "date '+%Y-%m-%d %H:%M:%S'" | tr -d '\r')

# Warmup and test at the given rate, plus time for logon and JIT
RUN_TIMEOUT=$(( (WARMUP + TEST_ORDERS) / (RATE > 0 ? RATE : 1000) + 300 ))
FIX_LOG="$OUT_DIR/fix-latency.log"

# Samplers run on core 0, away from the cores they measure. The initiator's
# ends with the latency run and marks the measured phase from its log.
SIM_CHECK_CORE=-1
[ "$TUNE_SIM" = true ] && SIM_CHECK_CORE=$SIM_CPU
if [ "$HOST_CHECK" = true ]; then
    for h in ouch fix; do
        on_$h "rm -f /tmp/host-check.stop /tmp/host-check.out /tmp/fix-latency-run.log; cat > /tmp/latency-host-check.sh" \
            < "$SCRIPT_DIR/latency-host-check.sh"
    done
    on_ouch "nohup taskset -c 0 bash /tmp/latency-host-check.sh $INITIATOR_CPU '[o]mnibridge.latency-test' $RUN_TIMEOUT \
        /tmp/fix-latency-run.log > /tmp/host-check.out 2>&1 < /dev/null &"
    on_fix "nohup taskset -c 0 bash /tmp/latency-host-check.sh $SIM_CHECK_CORE service:exchange-simulator $RUN_TIMEOUT \
        > /tmp/host-check.out 2>&1 < /dev/null &"
fi

# JVM flags match the fix-initiator systemd unit in setup-remote.sh
on_ouch "set -o pipefail; cd $INIT_DIR && ${SUDO}timeout $RUN_TIMEOUT /usr/bin/java \
    -Domnibridge.latency-test=true \
    -Dfix-engine.stage-timing.enabled=$STAGES \
    -Xms512m -Xmx1g \
    -XX:+UseZGC \
    --sun-misc-unsafe-memory-access=allow \
    --enable-native-access=ALL-UNNAMED \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
    --add-opens java.base/java.io=ALL-UNNAMED \
    --add-opens java.base/java.nio=ALL-UNNAMED \
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
    --add-exports java.base/jdk.internal.ref=ALL-UNNAMED \
    --add-exports java.base/jdk.internal.util=ALL-UNNAMED \
    --add-exports java.base/sun.nio.ch=ALL-UNNAMED \
    -Dlogback.configurationFile=$INIT_DIR/conf/logback.xml \
    -cp '$INIT_DIR/conf:$INIT_DIR/lib/*' \
    com.omnibridge.apps.fix.initiator.SampleInitiator \
    -c $INIT_DIR/conf/initiator-aws.conf,$OVERRIDE \
    --latency --warmup-orders $WARMUP --test-orders $TEST_ORDERS --rate $RATE --sender $SENDER 2>&1 \
    | tee /tmp/fix-latency-run.log" 2>&1 | tee "$FIX_LOG"
FIX_RC=${PIPESTATUS[0]}

# "[EXCH1->CLIENT1] read cap reached N times, cap 2048", logged when the initiator's Logout arrives
SIM_READ_CAP_LOG="$OUT_DIR/simulator-read-cap.txt"
on_fix "journalctl -u exchange-simulator --since '$SIM_RUN_START' --no-pager -o cat 2>/dev/null | grep 'read cap reached'" > "$SIM_READ_CAP_LOG" 2>/dev/null || true

# The simulator logs its stage report (one block per session) at the initiator's Logout
if [ "$STAGES" = true ]; then
    on_fix "journalctl -u exchange-simulator --since '$SIM_RUN_START' --no-pager -o cat 2>/dev/null \
        | grep -E 'Stage timing \\[|  stage '" > "$OUT_DIR/simulator-stages.txt" 2>/dev/null || true
fi

if [ "$HOST_CHECK" = true ]; then
    for h in ouch fix; do
        on_$h "touch /tmp/host-check.stop
               for i in 1 2 3 4 5; do grep -qE '^(done|error)' /tmp/host-check.out && break; sleep 1; done
               cat /tmp/host-check.out" > "$OUT_DIR/host-check-$h.txt" 2>&1
    done
fi

# -----------------------------------------------------------------------------
# Summary
# -----------------------------------------------------------------------------

# sockperf: "sockperf: ---> percentile 50.000 =   25.123" and "<MIN> observation = ..."
sp() { tr -d '\r' < "$SP_LOG" 2>/dev/null | awk -v k="$1" 'index($0, k) { v = $NF } END { print (v == "" ? "-" : v) }'; }
# LatencyTestRunner: "  | p50         |       183.45 |" (thousands separated)
fx() { tr -d '\r' < "$FIX_LOG" | awk -v k="$1" '$1 == "|" && $2 == k { v = $4 } END { gsub(",", "", v); print (v == "" ? "-" : v) }'; }

if [ "$BASELINE" = true ] && [ -s "$SP_LOG" ]; then
    SP_ROW=("$(sp '<MIN> observation')" "$(sp 'percentile 50.000')" "$(sp 'percentile 90.000')" \
            "$(sp 'percentile 99.000')" "$(sp '<MAX> observation')")
else
    SP_ROW=(- - - - -)
fi
FX_ROW=("$(fx Min)" "$(fx p50)" "$(fx p90)" "$(fx p99)" "$(fx Max)")
# HdrHistogram lines after the table: "  RTT from intended send (CO-corrected), us: count=… min=… p50=… …"
hist() { tr -d '\r' < "$FIX_LOG" | awk -v k="$1" 'index($0, k) == 1 { sub(/^[^:]*: /, ""); v = $0 } END { print v }'; }
HIST_INTENDED=$(hist '  RTT from intended send')
HIST_ACTUAL=$(hist '  RTT from actual send')
HIST_SLIP=$(hist '  Send slip')
hist_val() { sed -n "s/.*\(^\| \)$2=\([^ ]*\).*/\2/p" <<< "$1" | grep . || echo -; }
# Reads that returned the full read cap: initiator "  Read cap hits: initiator=N" (timed phase),
# simulator "[EXCH1->CLIENT1] read cap reached N times, cap 2048" (whole connection); "" when absent
READ_CAP_INITIATOR=$(tr -d '\r' < "$FIX_LOG" | sed -n 's/^  Read cap hits: initiator=\([0-9]*\).*/\1/p' | tail -1)
READ_CAP_SIM_LINE=$(tr -d '\r' < "$SIM_READ_CAP_LOG" 2>/dev/null | grep 'EXCH1->CLIENT1' | tail -1)
READ_CAP_SIMULATOR=$(sed -n 's/.*read cap reached \([0-9]*\) times, cap \([0-9]*\).*/\1/p' <<< "$READ_CAP_SIM_LINE")
READ_CAP_BYTES=$(sed -n 's/.*read cap reached \([0-9]*\) times, cap \([0-9]*\).*/\2/p' <<< "$READ_CAP_SIM_LINE")
FI_ROW=("$(hist_val "$HIST_INTENDED" min)" "$(hist_val "$HIST_INTENDED" p50)" "$(hist_val "$HIST_INTENDED" p90)" \
        "$(hist_val "$HIST_INTENDED" p99)" "$(hist_val "$HIST_INTENDED" max)")

# Reduces latency-host-check.sh output to "key<TAB>value" lines over the
# measured phase [start, end] (all samples when the marks are missing)
host_summary() {
    local file="$1" core="$2" spin="$3" start="$4" end="$5"
    tr -d '\r' < "$file" | awk -v core="$core" -v spin="$spin" -v s="${start:-0}" -v e="${end:-9999999999}" '
    function inlist(c, list,   n, i, a, r) {
        n = split(list, a, ",")
        for (i = 1; i <= n; i++) {
            if (split(a[i], r, "-") == 2) { if (c + 0 >= r[1] + 0 && c + 0 <= r[2] + 0) return 1 }
            else if (a[i] != "" && a[i] + 0 == c + 0) return 1
        }
        return 0
    }
    function out(k, v) { printf "%s\t%s\n", k, v }
    $1 == "error" { err = $0 }
    $1 == "static" && $2 == "nic_irq" {
        nic = nic (nic == "" ? "" : " ") $3 "=" $4
        if (core >= 0 && inlist(core, $4)) nicbad++
        next
    }
    $1 == "static" { k = $2; $1 = ""; $2 = ""; sub(/^ +/, ""); sub(/ +$/, ""); st[k] = $0; next }
    $1 == "sample" && $2 >= s && $2 <= e {
        n++
        if (n == 1) { t0 = $2; k0 = $4; v0 = $5; nv0 = $6; d0 = $7; l0 = $8 }
        t1 = $2; k1 = $4; v1 = $5; nv1 = $6; d1 = $7; l1 = $8
        if (!($3 in seen)) { seen[$3] = 1; ran = ran (ran == "" ? "" : ",") $3 }
        if ($9 > others) others = $9
    }
    END {
        if (err != "" || n < 2) { out("status", err != "" ? err : "too few samples"); exit }
        dt = t1 - t0; clk = (st["clk_tck"] > 0 ? st["clk_tck"] : 100)
        cpu = (k1 - k0) * 100 / (clk * dt); invol = (nv1 - nv0) / dt
        out("samples", n); out("seconds", dt); out("core", core)
        out("allowed", st["allowed"]); out("ran_on", ran); out("cpu_pct", sprintf("%.0f", cpu))
        out("vol_cs_per_s", sprintf("%.1f", (v1 - v0) / dt)); out("invol_cs_per_s", sprintf("%.1f", invol))
        out("dev_irq_per_s", sprintf("%.1f", (d1 - d0) / dt)); out("timer_irq_per_s", sprintf("%.0f", (l1 - l0) / dt))
        out("max_other_runnable", others + 0)
        out("isolated", st["isolated"]); out("nohz_full", st["nohz_full"]); out("cmdline", st["cmdline"])
        out("siblings", st["siblings"]); out("irqbalance", st["irqbalance"]); out("nic_irqs", nic)
        if (core < 0) {
            out("affinity", "n/a"); out("isolation", "n/a"); out("nic_irq_check", "n/a")
        } else {
            out("affinity", (st["allowed"] == core && ran == core) ? "PASS" : "FAIL")
            # The core and its hyperthread sibling(s) must all be isolated
            iso = inlist(core, st["isolated"]); ns = split(st["siblings"], sb, ",")
            for (i = 1; i <= ns; i++) {
                if (split(sb[i], r, "-") == 2) { for (c = r[1]; c <= r[2]; c++) iso = iso && inlist(c, st["isolated"]) }
                else iso = iso && inlist(sb[i], st["isolated"])
            }
            out("isolation", iso ? "PASS" : "WARN")
            out("nic_irq_check", (nicbad > 0 || st["irqbalance"] == "active") ? "WARN" : "PASS")
        }
        out("busy_spin", spin == "true" ? (cpu >= 90 ? "PASS" : "FAIL") : "n/a")
        out("preemption", (invol <= 1 && others == 0) ? "PASS" : "WARN")
        out("status", "ok")
    }'
}

declare -A HC_OUCH HC_FIX
if [ "$HOST_CHECK" = true ]; then
    MARK_START=$(tr -d '\r' < "$OUT_DIR/host-check-ouch.txt" | awk '$1 == "mark" && $2 == "start" {print $3}')
    MARK_END=$(tr -d '\r' < "$OUT_DIR/host-check-ouch.txt" | awk '$1 == "mark" && $2 == "end" {print $3}')
    host_summary "$OUT_DIR/host-check-ouch.txt" "$INITIATOR_CPU" true "$MARK_START" "$MARK_END" \
        > "$OUT_DIR/host-check-ouch.tsv"
    host_summary "$OUT_DIR/host-check-fix.txt" "$SIM_CHECK_CORE" "$TUNE_SIM" "$MARK_START" "$MARK_END" \
        > "$OUT_DIR/host-check-fix.tsv"
    while IFS=$'\t' read -r k v; do HC_OUCH[$k]="$v"; done < "$OUT_DIR/host-check-ouch.tsv"
    while IFS=$'\t' read -r k v; do HC_FIX[$k]="$v"; done < "$OUT_DIR/host-check-fix.tsv"
fi
hc_row() { printf "  %-26s %-28s %-28s\n" "$@"; }

{
    echo ""
    echo "================================================================"
    echo "  Round trip, microseconds"
    echo "================================================================"
    printf "  %-24s %9s %9s %9s %9s %9s\n" "" min p50 p90 p99 max
    printf "  %-24s %9s %9s %9s %9s %9s\n" "TCP (sockperf ${SP_SIZE}B)" "${SP_ROW[@]}"
    printf "  %-24s %9s %9s %9s %9s %9s\n" "FIX round trip" "${FX_ROW[@]}"
    if [ -n "$HIST_INTENDED" ]; then
        printf "  %-24s %9s %9s %9s %9s %9s\n" "FIX from intended send" "${FI_ROW[@]}"
        echo "  (intended send corrects for coordinated omission; send slip p50/p99: $(hist_val "$HIST_SLIP" p50)/$(hist_val "$HIST_SLIP" p99))"
    fi
    echo "  Read cap hits: initiator ${READ_CAP_INITIATOR:-n/a} (timed phase), simulator ${READ_CAP_SIMULATOR:-n/a} (whole connection)$([ -n "$READ_CAP_BYTES" ] && echo ", cap $READ_CAP_BYTES bytes")"
    echo "================================================================"
    if [ "$HOST_CHECK" = true ]; then
        hc_row "Host checks" "initiator (core $INITIATOR_CPU)" \
            "simulator ($([ "$SIM_CHECK_CORE" -ge 0 ] && echo "core $SIM_CHECK_CORE" || echo unpinned))"
        if [ "${HC_OUCH[status]:-}" != ok ] || [ "${HC_FIX[status]:-}" != ok ]; then
            hc_row "sampler" "${HC_OUCH[status]:-no output}" "${HC_FIX[status]:-no output}"
        fi
        hc_row "affinity (ran on)" "${HC_OUCH[affinity]:--} (${HC_OUCH[ran_on]:--})" \
            "${HC_FIX[affinity]:--} (${HC_FIX[ran_on]:--})"
        hc_row "busy-spin (CPU)" "${HC_OUCH[busy_spin]:--} (${HC_OUCH[cpu_pct]:--}%)" \
            "${HC_FIX[busy_spin]:--} (${HC_FIX[cpu_pct]:--}%)"
        hc_row "isolation (isolated)" "${HC_OUCH[isolation]:--} (${HC_OUCH[isolated]:-none})" \
            "${HC_FIX[isolation]:--} (${HC_FIX[isolated]:-none})"
        hc_row "NIC IRQs (irqbalance)" "${HC_OUCH[nic_irq_check]:--} (${HC_OUCH[irqbalance]:--})" \
            "${HC_FIX[nic_irq_check]:--} (${HC_FIX[irqbalance]:--})"
        hc_row "preemption (invol cs/s)" "${HC_OUCH[preemption]:--} (${HC_OUCH[invol_cs_per_s]:--})" \
            "${HC_FIX[preemption]:--} (${HC_FIX[invol_cs_per_s]:--})"
        hc_row "IRQs/s on core (dev/timer)" "${HC_OUCH[dev_irq_per_s]:--} / ${HC_OUCH[timer_irq_per_s]:--}" \
            "${HC_FIX[dev_irq_per_s]:--} / ${HC_FIX[timer_irq_per_s]:--}"
        hc_row "window" "${HC_OUCH[seconds]:--}s, ${HC_OUCH[samples]:--} samples" \
            "${HC_FIX[seconds]:--}s, ${HC_FIX[samples]:--} samples"
        echo "================================================================"
    fi
    if [ "$STAGES" = true ]; then
        echo "  Simulator stage timing ($OUT_DIR/simulator-stages.txt):"
        tr -d '\r' < "$OUT_DIR/simulator-stages.txt" 2>/dev/null | sed 's/^/  /'
        echo "================================================================"
    fi
    echo "  Simulator tuning: $TUNE_SIM; stage timing: $STAGES; persistence: $PERSIST; NIC tuning: $NIC_TUNING; sender: $SENDER; pretouch: $PRETOUCH; transport: $TRANSPORT; orders: warmup $WARMUP, test $TEST_ORDERS at $RATE/s"
    echo "  Logs: $OUT_DIR"
} | tee "$OUT_DIR/summary.txt"

# Machine-readable copy, one line per run in the history file, so runs can be
# compared across commits and tuning options
INSTANCE_TYPE=$(on_ouch 'T=$(curl -s -m 2 -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 60"); curl -s -m 2 -H "X-aws-ec2-metadata-token: $T" http://169.254.169.254/latest/meta-data/instance-type' 2>/dev/null | tr -d '\r')
COMMIT=$(git -C "$PROJECT_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)
BRANCH=$(git -C "$PROJECT_DIR" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)
DIRTY=$([ -n "$(git -C "$PROJECT_DIR" status --porcelain -- . ':!logs' 2>/dev/null)" ] && echo true || echo false)
jq -nc \
    --arg time "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg commit "$COMMIT" --arg branch "$BRANCH" \
    --argjson dirty "$DIRTY" --arg instance "${INSTANCE_TYPE:-unknown}" \
    --argjson tune "$TUNE_SIM" --argjson persist "$PERSIST" --argjson stages "$STAGES" --argjson nic "$NIC_TUNING" --arg sender "$SENDER" --argjson pretouch "$PRETOUCH" --arg transport "$TRANSPORT" --argjson warmup "$WARMUP" --argjson orders "$TEST_ORDERS" --argjson rate "$RATE" \
    --argjson icpu "$INITIATOR_CPU" --argjson scpu "$SIM_CPU" --argjson spsize "$SP_SIZE" --argjson rc "$FIX_RC" \
    --arg sp "${SP_ROW[*]}" --arg fx "${FX_ROW[*]}" \
    --arg hi "$HIST_INTENDED" --arg ha "$HIST_ACTUAL" --arg hs "$HIST_SLIP" \
    --arg rci "${READ_CAP_INITIATOR:-}" --arg rcs "${READ_CAP_SIMULATOR:-}" '
    def stats: split(" ") | map(if . == "-" then null else tonumber end)
               | {min: .[0], p50: .[1], p90: .[2], p99: .[3], max: .[4]};
    # "count=… min=… p50=… …" -> object; null when the initiator did not print it
    def hist: if . == "" then null
              else split(" ") | map(select(contains("=")) | split("=") | {(.[0]): (.[1] | tonumber? // .)}) | add end;
    {time: $time, commit: $commit, branch: $branch, dirty: $dirty, instance_type: $instance,
     options: {tune_simulator: $tune, persistence: $persist, stage_timing: $stages, nic_tuning: $nic, sender: $sender, pretouch: $pretouch, transport: $transport, warmup: $warmup, test_orders: $orders, rate: $rate,
               initiator_cpu: $icpu, simulator_cpu: (if $tune then $scpu else null end)},
     tcp_rtt_us: ($sp | stats) + {size_bytes: $spsize},
     fix_rtt_us: ($fx | stats), fix_rtt_intended_us: ($hi | hist), fix_rtt_actual_us: ($ha | hist),
     send_slip_us: ($hs | hist),
     read_cap_hits: {initiator: ($rci | tonumber? // null), simulator: ($rcs | tonumber? // null)},
     fix_exit_code: $rc}' | tr -d '\r' > "$OUT_DIR/summary.json"
if [ "$HOST_CHECK" = true ] && [ -s "$OUT_DIR/summary.json" ]; then
    # key<TAB>value lines -> object, numbers kept numeric
    tsv_json() { jq -Rn '[inputs | split("\t") | {(.[0]): (.[1] | tonumber? // .)}] | add // {}' < "$1" | tr -d '\r'; }
    jq -c --argjson i "$(tsv_json "$OUT_DIR/host-check-ouch.tsv")" --argjson s "$(tsv_json "$OUT_DIR/host-check-fix.tsv")" \
        '. + {host_checks: {initiator: $i, simulator: $s}}' < "$OUT_DIR/summary.json" | tr -d '\r' \
        > "$OUT_DIR/summary.json.tmp" && mv "$OUT_DIR/summary.json.tmp" "$OUT_DIR/summary.json"
fi
if [ -n "$HISTORY" ] && [ -s "$OUT_DIR/summary.json" ]; then
    mkdir -p "$(dirname "$HISTORY")"
    cat "$OUT_DIR/summary.json" >> "$HISTORY"
fi

if [ "$FIX_RC" -ne 0 ] || [ "${FX_ROW[1]}" = "-" ]; then
    echo "  FIX run failed (exit $FIX_RC); see $FIX_LOG"
    exit 1
fi
if [ "$HOST_CHECK" = true ]; then
    # FAIL means the pinning or busy-spin under test did not take effect
    CHECKS=" ${HC_OUCH[affinity]:-} ${HC_OUCH[busy_spin]:-} ${HC_FIX[affinity]:-} ${HC_FIX[busy_spin]:-} "
    if [ "${HC_OUCH[status]:-}" != ok ] || [[ "$CHECKS" == *" FAIL "* ]]; then
        echo "  Host check failed; see $OUT_DIR/host-check-*.txt"
        exit 1
    fi
    if [ "$REQUIRE_ISOLATION" = true ]; then
        CHECKS=" ${HC_OUCH[isolation]:-} ${HC_OUCH[nic_irq_check]:-} ${HC_OUCH[preemption]:-} "
        [ "$TUNE_SIM" = true ] && CHECKS="$CHECKS${HC_FIX[isolation]:-} ${HC_FIX[nic_irq_check]:-} ${HC_FIX[preemption]:-} "
        if [[ "$CHECKS" == *" WARN "* ]]; then
            echo "  Isolation required but not in place (--require-isolation)"
            exit 1
        fi
    fi
fi
