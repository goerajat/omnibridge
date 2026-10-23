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
#   3. Start the simulator again, optionally with busy-spin, cpu-affinity and
#      log-messages = false on FIX-SIM-1 (its exchange-simulator.conf is backed
#      up and restored afterwards).
#   4. Run SampleInitiator --latency with a tuned override conf (busy-spin,
#      cpu-affinity, log-messages = false, reset-on-logon = true).
#   5. Realign FIX-SIM-1's sequence numbers through the simulator admin API so
#      the restarted demo initiator does not disconnect, then restart it.
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
#   --tune-simulator      Also busy-spin/pin the simulator, no FIX-SIM-1 logging
#   --simulator-cpu       Simulator event loop core (default: 3)
#   --skip-baseline       Skip the sockperf measurement
#   --sockperf-time       sockperf duration in seconds (default: 30)
#   --sockperf-size       sockperf message size in bytes (default: 200)
#   --sockperf-port       sockperf TCP port (default: 11111)
#   --outgoing-seq        Simulator outgoing seq set after the run (default: 1000000)
#   -o, --output-dir      Local results directory (default: logs/aws-latency/<time>)
#
# Examples:
#   ./run-aws-latency-test.sh
#   ./run-aws-latency-test.sh -w 20000 -n 5000 -r 500 --tune-simulator
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
TUNE_SIM=false
SIM_CPU=3
BASELINE=true
SP_TIME=30
SP_SIZE=200
SP_PORT=11111
OUT_SEQ=1000000
OUT_DIR=""

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
        --tune-simulator) TUNE_SIM=true;      shift ;;
        --simulator-cpu)  SIM_CPU="$2";       shift 2 ;;
        --skip-baseline)  BASELINE=false;     shift ;;
        --sockperf-time)  SP_TIME="$2";       shift 2 ;;
        --sockperf-size)  SP_SIZE="$2";       shift 2 ;;
        --sockperf-port)  SP_PORT="$2";       shift 2 ;;
        --outgoing-seq)   OUT_SEQ="$2";       shift 2 ;;
        -o|--output-dir)  OUT_DIR="$2";       shift 2 ;;
        -h|--help)        sed -n '2,46p' "$0"; exit 0 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# -----------------------------------------------------------------------------
# Prerequisites
# -----------------------------------------------------------------------------

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

echo "================================================================"
echo "  AWS latency test"
echo "================================================================"
echo "  FIX host (simulator):   $FIX_IP"
echo "  OUCH host (initiator):  $OUCH_IP"
echo "  Orders:                 warmup $WARMUP, test $TEST_ORDERS at $RATE/s"
echo "  Initiator core:         $INITIATOR_CPU"
echo "  Simulator tuning:       $([ "$TUNE_SIM" = true ] && echo "on, core $SIM_CPU" || echo off)"
echo "  Baseline (sockperf):    $([ "$BASELINE" = true ] && echo "${SP_SIZE}B for ${SP_TIME}s on port $SP_PORT" || echo skipped)"
echo "  Results:                $OUT_DIR"
echo ""

# -----------------------------------------------------------------------------
# Cleanup: always put both services back the way the demo expects them
# -----------------------------------------------------------------------------

INITIATOR_STOPPED=false
SIM_STOPPED=false
SIM_TUNED=false
CLEANED=false

# Waits until FIX-SIM-1 is known to the admin API and the FIX port listens
wait_for_simulator() {
    on_fix "bash -s" <<REMOTE
for i in \$(seq 1 90); do
    code=\$(curl -s -o /dev/null -w '%{http_code}' '$SESSION_URL' 2>/dev/null)
    if [ "\$code" = 200 ] && ss -ltn 2>/dev/null | grep -q ':9876 '; then exit 0; fi
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

    # A latency run cut short by Ctrl-C would keep its session open
    on_ouch "pkill -f '[o]mnibridge.latency-test' || true" 2>/dev/null
    on_fix "pkill -x sockperf || true" 2>/dev/null

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
    echo "[3/4] Tuning simulator (busy-spin, core $SIM_CPU, FIX-SIM-1 log-messages = false)"
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
}

fix-engine {
    sessions = [
        { session-id = "FIX-SIM-1", port = 9876, sender-comp-id = "EXCH1", target-comp-id = "CLIENT1", initiator = false, begin-string = "FIX.4.4", heartbeat-interval = 30, log-messages = false },
        { session-id = "FIX-SIM-2", port = 9876, sender-comp-id = "EXCH2", target-comp-id = "CLIENT2", initiator = false, begin-string = "FIX.4.4", heartbeat-interval = 30 },
        { session-id = "FIX-SIM-3", port = 9876, sender-comp-id = "EXCH3", target-comp-id = "CLIENT3", initiator = false, begin-string = "FIX.4.4", heartbeat-interval = 30 },
        { session-id = "FIX-SIM-4", port = 9876, sender-comp-id = "EXCH4", target-comp-id = "CLIENT4", initiator = false, begin-string = "FIX.4.4", heartbeat-interval = 30 },
        { session-id = "FIX-SIM-5", port = 9876, sender-comp-id = "EXCH5", target-comp-id = "CLIENT5", initiator = false, begin-string = "FIX.4.4", heartbeat-interval = 30 }
    ]
}
CONF
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
    echo '}'
    echo 'persistence {'
    echo '    base-path = "$INIT_DIR/data/latency-logs"'
    echo '}'
    tr -d '\r' < $INIT_DIR/conf/initiator-aws.conf | sed -n '/^fix-engine {/,\$p' \
        | sed 's/^\( *\)initiator = true.*/&\n\1reset-on-logon = true\n\1log-messages = false/'
} > $OVERRIDE
grep -q 'reset-on-logon = true' $OVERRIDE
rm -rf $INIT_DIR/data/latency-logs
REMOTE
then
    echo "  FAIL: could not write $OVERRIDE from initiator-aws.conf"
    exit 1
fi

# Warmup and test at the given rate, plus time for logon and JIT
RUN_TIMEOUT=$(( (WARMUP + TEST_ORDERS) / (RATE > 0 ? RATE : 1000) + 300 ))
FIX_LOG="$OUT_DIR/fix-latency.log"

# JVM flags match the fix-initiator systemd unit in setup-remote.sh
on_ouch "cd $INIT_DIR && timeout $RUN_TIMEOUT /usr/bin/java \
    -Domnibridge.latency-test=true \
    -Xms512m -Xmx1g \
    -XX:+UseZGC \
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
    --latency --warmup-orders $WARMUP --test-orders $TEST_ORDERS --rate $RATE" 2>&1 | tee "$FIX_LOG"
FIX_RC=${PIPESTATUS[0]}

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

{
    echo ""
    echo "================================================================"
    echo "  Round trip, microseconds"
    echo "================================================================"
    printf "  %-24s %9s %9s %9s %9s %9s\n" "" min p50 p90 p99 max
    printf "  %-24s %9s %9s %9s %9s %9s\n" "TCP (sockperf ${SP_SIZE}B)" "${SP_ROW[@]}"
    printf "  %-24s %9s %9s %9s %9s %9s\n" "FIX round trip" "${FX_ROW[@]}"
    echo "================================================================"
    echo "  Simulator tuning: $TUNE_SIM; orders: warmup $WARMUP, test $TEST_ORDERS at $RATE/s"
    echo "  Logs: $OUT_DIR"
} | tee "$OUT_DIR/summary.txt"

if [ "$FIX_RC" -ne 0 ] || [ "${FX_ROW[1]}" = "-" ]; then
    echo "  FIX run failed (exit $FIX_RC); see $FIX_LOG"
    exit 1
fi
