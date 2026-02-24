#!/bin/bash
# =============================================================================
# Service Management Script
# =============================================================================
# Remotely start, stop, restart, or check status of all OmniBridge services.
#
# Usage: ./manage-services.sh -f <tf-outputs.json> -i <pem-file> <command> [component...]
#
# Commands: start, stop, restart, status
#
# Components: all (default), exchange-simulator, fix-initiator, aeron-store,
#             omniview, monitoring
#
# Options:
#   -f, --tf-outputs   Terraform output JSON file (required)
#   -i, --identity     PEM file for SSH authentication (required)
#   -u, --user         SSH username (default: ubuntu)
#   --ssh-port         SSH port (default: 22)
#
# Examples:
#   ./manage-services.sh -f tf-outputs.json -i key.pem status
#   ./manage-services.sh -f tf-outputs.json -i key.pem start
#   ./manage-services.sh -f tf-outputs.json -i key.pem stop exchange-simulator
#   ./manage-services.sh -f tf-outputs.json -i key.pem restart aeron-store
#   ./manage-services.sh -f tf-outputs.json -i key.pem status omniview monitoring
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Defaults
SSH_USER="ubuntu"
SSH_PORT=22
TF_OUTPUTS=""
PEM_FILE=""
COMMAND=""
COMPONENTS=()

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        -f|--tf-outputs)  TF_OUTPUTS="$2"; shift 2 ;;
        -i|--identity)    PEM_FILE="$2";   shift 2 ;;
        -u|--user)        SSH_USER="$2";   shift 2 ;;
        --ssh-port)       SSH_PORT="$2";   shift 2 ;;
        --help)
            echo "Service Management Script"
            echo ""
            echo "Usage: $0 -f <tf-outputs.json> -i <pem-file> <command> [component...]"
            echo ""
            echo "Commands: start, stop, restart, status"
            echo ""
            echo "Components: all (default), exchange-simulator, fix-initiator,"
            echo "            aeron-store, omniview, monitoring"
            echo ""
            echo "Options:"
            echo "  -f, --tf-outputs   Terraform output JSON file (required)"
            echo "  -i, --identity     PEM file for SSH authentication (required)"
            echo "  -u, --user         SSH username (default: ubuntu)"
            echo "  --ssh-port         SSH port (default: 22)"
            exit 0
            ;;
        start|stop|restart|status)
            COMMAND="$1"
            shift
            ;;
        -*)
            echo "Error: Unknown option: $1"
            exit 1
            ;;
        *)
            COMPONENTS+=("$1")
            shift
            ;;
    esac
done

# Validate
if [ -z "$COMMAND" ]; then
    echo "Error: Command required (start, stop, restart, status)"
    echo "Run '$0 --help' for usage."
    exit 1
fi
if [ -z "$TF_OUTPUTS" ]; then
    echo "Error: --tf-outputs is required"
    exit 1
fi
if [ -z "$PEM_FILE" ]; then
    echo "Error: --identity is required"
    exit 1
fi
if [ ! -f "$TF_OUTPUTS" ]; then
    echo "Error: Terraform output file not found: $TF_OUTPUTS"
    exit 1
fi
if [ ! -f "$PEM_FILE" ]; then
    echo "Error: PEM file not found: $PEM_FILE"
    exit 1
fi
if ! command -v jq &> /dev/null; then
    echo "Error: jq is required. Install with: sudo apt install jq / brew install jq"
    exit 1
fi

# Default to all components
if [ ${#COMPONENTS[@]} -eq 0 ]; then
    COMPONENTS=("all")
fi

# Extract IPs
FIX_IP=$(jq -r '.fix_acceptor_private_ip.value' "$TF_OUTPUTS")
OUCH_IP=$(jq -r '.ouch_acceptor_private_ip.value' "$TF_OUTPUTS")
AERON_IP=$(jq -r '.aeron_persistence_private_ip.value' "$TF_OUTPUTS")
MONITORING_PUBLIC_IP=$(jq -r '.monitoring_public_ip.value' "$TF_OUTPUTS")

SSH_OPTS="-i $PEM_FILE -o StrictHostKeyChecking=no -o ConnectTimeout=10 -p $SSH_PORT"
SSH_JUMP="-o ProxyJump=$SSH_USER@$MONITORING_PUBLIC_IP:$SSH_PORT"

# -------------------------------------------------------------------------
# Service definitions: component -> (host, jump_mode, service_name, label)
# -------------------------------------------------------------------------
# Order matters for start (dependencies first) and stop (reverse).

ALL_COMPONENTS_ORDERED=(aeron-store exchange-simulator fix-initiator omniview monitoring)

declare -A SVC_HOST SVC_JUMP SVC_NAME SVC_LABEL SVC_TYPE

SVC_HOST[aeron-store]="$AERON_IP"
SVC_JUMP[aeron-store]="jump"
SVC_NAME[aeron-store]="aeron-remote-store"
SVC_LABEL[aeron-store]="Aeron Remote Store"
SVC_TYPE[aeron-store]="systemd"

SVC_HOST[exchange-simulator]="$FIX_IP"
SVC_JUMP[exchange-simulator]="jump"
SVC_NAME[exchange-simulator]="exchange-simulator"
SVC_LABEL[exchange-simulator]="Exchange Simulator"
SVC_TYPE[exchange-simulator]="systemd"

SVC_HOST[fix-initiator]="$OUCH_IP"
SVC_JUMP[fix-initiator]="jump"
SVC_NAME[fix-initiator]="fix-initiator"
SVC_LABEL[fix-initiator]="FIX Initiator"
SVC_TYPE[fix-initiator]="systemd"

SVC_HOST[omniview]="$MONITORING_PUBLIC_IP"
SVC_JUMP[omniview]="direct"
SVC_NAME[omniview]="omniview"
SVC_LABEL[omniview]="OmniView"
SVC_TYPE[omniview]="systemd"

SVC_HOST[monitoring]="$MONITORING_PUBLIC_IP"
SVC_JUMP[monitoring]="direct"
SVC_NAME[monitoring]=""
SVC_LABEL[monitoring]="Monitoring Stack"
SVC_TYPE[monitoring]="docker"

# -------------------------------------------------------------------------
# Helpers
# -------------------------------------------------------------------------

remote_exec() {
    local host="$1"
    local jump="$2"
    shift 2

    if [ "$jump" = "direct" ]; then
        ssh $SSH_OPTS "$SSH_USER@$host" "$@"
    else
        ssh $SSH_OPTS $SSH_JUMP "$SSH_USER@$host" "$@"
    fi
}

# Map command to systemctl action
systemctl_cmd() {
    local cmd="$1"
    case "$cmd" in
        start)   echo "start" ;;
        stop)    echo "stop" ;;
        restart) echo "restart" ;;
        status)  echo "status" ;;
    esac
}

# Map command to docker compose action
docker_cmd() {
    local cmd="$1"
    case "$cmd" in
        start)   echo "up -d" ;;
        stop)    echo "down" ;;
        restart) echo "restart" ;;
        status)  echo "ps" ;;
    esac
}

# Run a command on one component
manage_component() {
    local comp="$1"
    local cmd="$2"
    local host="${SVC_HOST[$comp]}"
    local jump="${SVC_JUMP[$comp]}"
    local svc="${SVC_NAME[$comp]}"
    local label="${SVC_LABEL[$comp]}"
    local stype="${SVC_TYPE[$comp]}"

    printf "  %-22s %-15s ... " "$label" "($host)"

    local rc=0
    if [ "$stype" = "systemd" ]; then
        local action=$(systemctl_cmd "$cmd")
        if [ "$cmd" = "status" ]; then
            # For status, capture output instead of failing on non-zero
            local output
            output=$(remote_exec "$host" "$jump" "sudo systemctl $action $svc 2>&1" 2>/dev/null) || true
            # Extract the Active: line
            local state=$(echo "$output" | grep -oP 'Active:\s+\K\S+ \([^)]+\)' 2>/dev/null || echo "unknown")
            echo "$state"
        else
            remote_exec "$host" "$jump" "sudo systemctl $action $svc" 2>/dev/null
            rc=$?
            if [ $rc -eq 0 ]; then
                echo "OK"
            else
                echo "FAILED"
            fi
        fi
    elif [ "$stype" = "docker" ]; then
        local action=$(docker_cmd "$cmd")
        if [ "$cmd" = "status" ]; then
            echo ""
            remote_exec "$host" "$jump" "cd /opt/monitoring && docker compose $action 2>/dev/null" 2>/dev/null || echo "    (docker compose not available)"
        else
            remote_exec "$host" "$jump" "cd /opt/monitoring && docker compose $action" 2>/dev/null
            rc=$?
            if [ $rc -eq 0 ]; then
                echo "OK"
            else
                echo "FAILED"
            fi
        fi
    fi

    return $rc
}

# -------------------------------------------------------------------------
# Resolve component list
# -------------------------------------------------------------------------

RESOLVED=()
for comp in "${COMPONENTS[@]}"; do
    if [ "$comp" = "all" ]; then
        RESOLVED=("${ALL_COMPONENTS_ORDERED[@]}")
        break
    else
        # Validate component name
        if [ -z "${SVC_HOST[$comp]+x}" ]; then
            echo "Error: Unknown component '$comp'"
            echo "Available: ${ALL_COMPONENTS_ORDERED[*]}"
            exit 1
        fi
        RESOLVED+=("$comp")
    fi
done

# For stop, reverse the order (stop dependents first)
if [ "$COMMAND" = "stop" ]; then
    REVERSED=()
    for (( i=${#RESOLVED[@]}-1; i>=0; i-- )); do
        REVERSED+=("${RESOLVED[$i]}")
    done
    RESOLVED=("${REVERSED[@]}")
fi

# -------------------------------------------------------------------------
# Execute
# -------------------------------------------------------------------------

echo "========================================================"
echo "OmniBridge Service Management: ${COMMAND^^}"
echo "========================================================"
echo "Jump host: $MONITORING_PUBLIC_IP"
echo ""

TOTAL=0
FAILURES=0

for comp in "${RESOLVED[@]}"; do
    manage_component "$comp" "$COMMAND" || FAILURES=$((FAILURES+1))
    TOTAL=$((TOTAL+1))
done

echo ""
echo "========================================================"
SUCCEEDED=$((TOTAL - FAILURES))
echo "Done: $SUCCEEDED/$TOTAL succeeded"
echo "========================================================"

if [ $FAILURES -gt 0 ]; then
    exit 1
fi
