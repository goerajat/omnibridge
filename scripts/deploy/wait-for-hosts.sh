#!/bin/bash
# =============================================================================
# Wait For Hosts
# =============================================================================
# Waits until the EC2 instances created by terraform-deploy.sh are ready for
# setup-remote.sh: AWS status checks pass, SSH answers, cloud-init has finished,
# and on the FIX, OUCH and Aeron hosts the GP3 data volume is mounted at /opt.
# Deploying before the volume is mounted would put the apps on the root disk,
# where the later mount over /opt would hide them.
#
# Usage: ./wait-for-hosts.sh [options]
#
# Options:
#   -f, --tf-outputs   Terraform output JSON file (default: tf-outputs.json)
#   -i, --identity     PEM file (default: omnibridge-key.pem)
#   -u, --user         SSH username (default: ubuntu)
#   --ssh-port         SSH port (default: 22)
#   -t, --timeout      Seconds to wait for all hosts (default: 900)
#   --skip-aws-check   Skip the AWS instance status check (no AWS CLI call)
#
# Exit code: 0 when every host is ready, 1 otherwise.
#
# Examples:
#   ./wait-for-hosts.sh
#   ./wait-for-hosts.sh -t 1200
# =============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Defaults
TF_OUTPUTS="$PROJECT_DIR/tf-outputs.json"
PEM_FILE="$PROJECT_DIR/omnibridge-key.pem"
SSH_USER="ubuntu"
SSH_PORT=22
TIMEOUT=900
AWS_CHECK=true

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        -f|--tf-outputs)  TF_OUTPUTS="$2"; shift 2 ;;
        -i|--identity)    PEM_FILE="$2";   shift 2 ;;
        -u|--user)        SSH_USER="$2";   shift 2 ;;
        --ssh-port)       SSH_PORT="$2";   shift 2 ;;
        -t|--timeout)     TIMEOUT="$2";    shift 2 ;;
        --skip-aws-check) AWS_CHECK=false; shift ;;
        -h|--help)        sed -n '2,25p' "$0"; exit 0 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# -----------------------------------------------------------------------------
# Prerequisites
# -----------------------------------------------------------------------------

if [ ! -f "$TF_OUTPUTS" ]; then
    echo "Error: Terraform output file not found: $TF_OUTPUTS"
    echo "  Run terraform-deploy.sh first, or specify -f <path>"
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
AERON_IP=$(out aeron_persistence_private_ip)
INSTANCE_IDS=$(jq -r '[.fix_acceptor_instance_id, .ouch_acceptor_instance_id,
                        .aeron_persistence_instance_id, .monitoring_instance_id]
                       | map(.value // empty) | join(" ")' < "$TF_OUTPUTS" | tr -d '\r')

if [ -z "$MONITORING_IP" ] || [ -z "$FIX_IP" ] || [ -z "$OUCH_IP" ] || [ -z "$AERON_IP" ]; then
    echo "Error: $TF_OUTPUTS is missing host IPs; re-run terraform-deploy.sh"
    exit 1
fi

SSH_OPTS="-i $PEM_FILE -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=10 -o BatchMode=yes -p $SSH_PORT"
PROXY_CMD="ssh -i $PEM_FILE -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -p $SSH_PORT -W %h:%p $SSH_USER@$MONITORING_IP"

DEADLINE=$(( $(date +%s) + TIMEOUT ))
remaining() { echo $(( DEADLINE - $(date +%s) )); }

echo "================================================================"
echo "  Waiting for hosts (timeout ${TIMEOUT}s)"
echo "================================================================"
echo "  Monitoring: $MONITORING_IP (public)"
echo "  FIX:        $FIX_IP"
echo "  OUCH:       $OUCH_IP"
echo "  Aeron:      $AERON_IP"
echo ""

# -----------------------------------------------------------------------------
# 1. AWS status checks (system + instance reachability)
# -----------------------------------------------------------------------------

if [ "$AWS_CHECK" = true ] && [ -n "$INSTANCE_IDS" ] && command -v aws &>/dev/null; then
    echo "[1/3] AWS status checks for: $INSTANCE_IDS"
    # instance-status-ok polls every 15s for up to 10 minutes per call
    until aws ec2 wait instance-status-ok --instance-ids $INSTANCE_IDS 2>/dev/null; do
        if [ "$(remaining)" -le 0 ]; then
            echo "  FAIL: instances did not pass AWS status checks in time"
            aws ec2 describe-instance-status --instance-ids $INSTANCE_IDS --include-all-instances \
                --query 'InstanceStatuses[].[InstanceId,InstanceState.Name,SystemStatus.Status,InstanceStatus.Status]' \
                --output table
            exit 1
        fi
    done
    echo "  OK: all instances running and passing status checks"
else
    echo "[1/3] AWS status checks skipped"
fi
echo ""

# -----------------------------------------------------------------------------
# 2-3. SSH, cloud-init and data volume, per host
# -----------------------------------------------------------------------------

# Runs on the host. Prints one line: cloud-init=<status> opt=<source> size=<size> bootstrap=<n> warn=<n>
REMOTE_CHECK='
cloud-init status --wait >/dev/null 2>&1
CI=$(cloud-init status 2>/dev/null | awk "/status:/ {print \$2}")
SRC=none; SIZE=-
if mountpoint -q /opt; then
    SRC=$(findmnt -no SOURCE /opt)
    SIZE=$(df -h --output=size /opt | tail -1 | tr -d " ")
fi
LOG=/var/log/omnibridge-bootstrap.log
DONE=$(sudo grep -c "bootstrap complete" $LOG 2>/dev/null || true)
WARN=$(sudo grep -c "^WARNING" $LOG 2>/dev/null || true)
echo "cloud-init=${CI:-unknown} opt=$SRC size=$SIZE bootstrap=${DONE:-0} warn=${WARN:-0}"
'

run_on() {
    local host="$1" jump="$2"; shift 2
    if [ "$jump" = "direct" ]; then
        ssh $SSH_OPTS "$SSH_USER@$host" "$@"
    else
        ssh $SSH_OPTS -o "ProxyCommand=$PROXY_CMD" "$SSH_USER@$host" "$@"
    fi
}

FAILED=0
RESULTS=()

check_host() {
    local label="$1" host="$2" jump="$3" needs_volume="$4"
    local status=""

    printf "[2/3] %-11s SSH ... " "$label"
    until run_on "$host" "$jump" true 2>/dev/null; do
        if [ "$(remaining)" -le 0 ]; then
            echo "FAIL (no SSH)"
            RESULTS+=("$(printf "FAIL  %-11s no SSH within %ss" "$label" "$TIMEOUT")")
            FAILED=$((FAILED + 1))
            return
        fi
        sleep 10
    done
    echo "ok"

    printf "[3/3] %-11s cloud-init and /opt ... " "$label"
    status=$(run_on "$host" "$jump" "bash -s" <<< "$REMOTE_CHECK" 2>/dev/null | tail -1)
    echo "$status"

    local ci opt bootstrap warn
    ci=$(sed -n 's/.*cloud-init=\([^ ]*\).*/\1/p' <<< "$status")
    opt=$(sed -n 's/.*opt=\([^ ]*\).*/\1/p' <<< "$status")
    bootstrap=$(sed -n 's/.*bootstrap=\([0-9]*\).*/\1/p' <<< "$status")
    warn=$(sed -n 's/.*warn=\([0-9]*\).*/\1/p' <<< "$status")

    local problems=()
    [ "$ci" = "done" ] || problems+=("cloud-init $ci")
    if [ "$needs_volume" = true ]; then
        [ "${bootstrap:-0}" -ge 1 ] || problems+=("bootstrap log has no completion line")
        [[ "$opt" == /dev/nvme* || "$opt" == /dev/xvd* ]] || problems+=("data volume not mounted at /opt ($opt)")
        [ "${warn:-0}" -eq 0 ] || problems+=("bootstrap logged a warning")
    fi

    if [ ${#problems[@]} -eq 0 ]; then
        RESULTS+=("$(printf "OK    %-11s %s" "$label" "$status")")
    else
        RESULTS+=("$(printf "FAIL  %-11s %s -- see /var/log/omnibridge-bootstrap.log" "$label" "$(IFS=';'; echo "${problems[*]}")")")
        FAILED=$((FAILED + 1))
    fi
}

# The monitoring host is the SSH bastion for the others, so it goes first
check_host "monitoring" "$MONITORING_IP" direct false
check_host "fix"        "$FIX_IP"        jump   true
check_host "ouch"       "$OUCH_IP"       jump   true
check_host "aeron"      "$AERON_IP"      jump   true

echo ""
echo "================================================================"
for r in "${RESULTS[@]}"; do echo "  $r"; done
echo "================================================================"

if [ "$FAILED" -gt 0 ]; then
    echo "  $FAILED host(s) not ready; do not run setup-remote.sh yet"
    exit 1
fi
echo "  All hosts ready for setup-remote.sh"
