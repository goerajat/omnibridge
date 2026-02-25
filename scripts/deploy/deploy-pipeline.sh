#!/bin/bash
# =============================================================================
# OmniBridge Deploy Pipeline
# =============================================================================
# Single script chaining the full build-upload-deploy-restart-validate cycle.
#
# Usage:
#   ./deploy-pipeline.sh                                     # Full pipeline
#   ./deploy-pipeline.sh --skip-build                        # Already built
#   ./deploy-pipeline.sh --skip-build --skip-upload          # Redeploy from S3
#   ./deploy-pipeline.sh --component exchange-simulator      # Single component
#   ./deploy-pipeline.sh --validate-only                     # Just check health
#
# Stages:
#   1. Build   - mvn install -DskipTests              (--skip-build)
#   2. Upload  - upload-artifacts.sh                   (--skip-upload)
#   3. Deploy  - setup-remote.sh --component X         (--skip-deploy)
#   4. Restart - manage-services.sh restart X           (--skip-restart)
#   5. Validate - health checks                        (always runs)
#
# Options:
#   -f, --tf-outputs   Terraform output JSON file (default: tf-outputs.json)
#   -i, --identity     PEM file (default: omnibridge-key.pem)
#   -u, --user         SSH username (default: ubuntu)
#   --component <name> Deploy/restart/validate only this component
#   --skip-build       Skip Maven build
#   --skip-upload      Skip S3 upload
#   --skip-deploy      Skip remote deployment
#   --skip-restart     Skip service restart
#   --validate-only    Skip stages 1-4, only run validation
#   --wait <secs>      Seconds to wait between restart and validate (default: 15)
#
# Components: aeron-store, exchange-simulator, fix-initiator, omniview, monitoring
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Defaults
TF_OUTPUTS="$PROJECT_DIR/tf-outputs.json"
PEM_FILE="$PROJECT_DIR/omnibridge-key.pem"
SSH_USER="ubuntu"
SSH_PORT=22
COMPONENT=""
SKIP_BUILD=false
SKIP_UPLOAD=false
SKIP_DEPLOY=false
SKIP_RESTART=false
VALIDATE_ONLY=false
RESTART_WAIT=15

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        -f|--tf-outputs)   TF_OUTPUTS="$2";  shift 2 ;;
        -i|--identity)     PEM_FILE="$2";    shift 2 ;;
        -u|--user)         SSH_USER="$2";    shift 2 ;;
        --ssh-port)        SSH_PORT="$2";    shift 2 ;;
        --component)       COMPONENT="$2";   shift 2 ;;
        --skip-build)      SKIP_BUILD=true;  shift ;;
        --skip-upload)     SKIP_UPLOAD=true; shift ;;
        --skip-deploy)     SKIP_DEPLOY=true; shift ;;
        --skip-restart)    SKIP_RESTART=true; shift ;;
        --validate-only)   VALIDATE_ONLY=true; shift ;;
        --wait)            RESTART_WAIT="$2"; shift 2 ;;
        --help)
            sed -n '2,/^# ====/{/^# /s/^# //p}' "$0"
            exit 0
            ;;
        *)
            echo "Error: Unknown option: $1"
            echo "Run '$0 --help' for usage."
            exit 1
            ;;
    esac
done

if [ "$VALIDATE_ONLY" = true ]; then
    SKIP_BUILD=true
    SKIP_UPLOAD=true
    SKIP_DEPLOY=true
    SKIP_RESTART=true
fi

# =========================================================================
# Validate prerequisites
# =========================================================================

if [ ! -f "$TF_OUTPUTS" ]; then
    echo "Error: Terraform output file not found: $TF_OUTPUTS"
    echo "  Run terraform-deploy.sh first, or specify -f <path>"
    exit 1
fi
if [ ! -f "$PEM_FILE" ]; then
    echo "Error: PEM file not found: $PEM_FILE"
    echo "  Specify -i <path>"
    exit 1
fi
if ! command -v jq &>/dev/null; then
    echo "Error: jq is required. Install with: sudo apt install jq / brew install jq"
    exit 1
fi

# Validate component name if specified
VALID_COMPONENTS=(aeron-store exchange-simulator fix-initiator omniview monitoring)
if [ -n "$COMPONENT" ]; then
    FOUND=false
    for c in "${VALID_COMPONENTS[@]}"; do
        if [ "$c" = "$COMPONENT" ]; then FOUND=true; break; fi
    done
    if [ "$FOUND" = false ]; then
        echo "Error: Unknown component '$COMPONENT'"
        echo "Valid components: ${VALID_COMPONENTS[*]}"
        exit 1
    fi
fi

# Resolve PEM to absolute path
PEM_FILE="$(cd "$(dirname "$PEM_FILE")" && pwd)/$(basename "$PEM_FILE")"

# Extract IPs
FIX_IP=$(jq -r '.fix_acceptor_private_ip.value' "$TF_OUTPUTS" | tr -d '\r')
OUCH_IP=$(jq -r '.ouch_acceptor_private_ip.value' "$TF_OUTPUTS" | tr -d '\r')
AERON_IP=$(jq -r '.aeron_persistence_private_ip.value' "$TF_OUTPUTS" | tr -d '\r')
MONITORING_IP=$(jq -r '.monitoring_public_ip.value' "$TF_OUTPUTS" | tr -d '\r')

SSH_OPTS="-i $PEM_FILE -o StrictHostKeyChecking=no -o ConnectTimeout=10 -p $SSH_PORT"
PROXY_CMD="ssh -i $PEM_FILE -o StrictHostKeyChecking=no -p $SSH_PORT -W %h:%p $SSH_USER@$MONITORING_IP"

# Component-to-upload-artifact name mapping
declare -A UPLOAD_MAP
UPLOAD_MAP[aeron-store]="aeron-remote-store"
UPLOAD_MAP[exchange-simulator]="exchange-simulator"
UPLOAD_MAP[fix-initiator]="fix-samples"
UPLOAD_MAP[omniview]="omniview"
# monitoring has no artifact to upload

# =========================================================================
# Helpers
# =========================================================================

PIPELINE_START=$(date +%s)
declare -A STAGE_TIME

stage_start() {
    STAGE_TIME[$1]=$(date +%s)
}

stage_end() {
    local name="$1"
    local end=$(date +%s)
    local elapsed=$((end - ${STAGE_TIME[$name]}))
    echo ""
    echo "  Stage '$name' completed in ${elapsed}s"
    echo ""
    STAGE_TIME[$name]=$elapsed
}

banner() {
    echo ""
    echo "================================================================"
    echo "  $1"
    echo "================================================================"
}

remote_exec() {
    local host="$1"
    local jump="$2"
    shift 2
    if [ "$jump" = "direct" ]; then
        ssh $SSH_OPTS "$SSH_USER@$host" "$@"
    else
        ssh $SSH_OPTS -o "ProxyCommand=$PROXY_CMD" "$SSH_USER@$host" "$@"
    fi
}

# =========================================================================
# Banner
# =========================================================================

echo ""
echo "================================================================"
echo "  OmniBridge Deploy Pipeline"
echo "================================================================"
echo "  TF Outputs:  $TF_OUTPUTS"
echo "  PEM File:    $PEM_FILE"
echo "  Component:   ${COMPONENT:-all}"
echo "  Stages:      $([ "$SKIP_BUILD" = true ] && echo "skip-build " || echo "build ")\
$([ "$SKIP_UPLOAD" = true ] && echo "skip-upload " || echo "upload ")\
$([ "$SKIP_DEPLOY" = true ] && echo "skip-deploy " || echo "deploy ")\
$([ "$SKIP_RESTART" = true ] && echo "skip-restart " || echo "restart ")\
validate"
echo ""
echo "  FIX host:        $FIX_IP"
echo "  OUCH host:       $OUCH_IP"
echo "  Aeron host:      $AERON_IP"
echo "  Monitoring host:  $MONITORING_IP"
echo "================================================================"
echo ""

# =========================================================================
# Stage 1: Build
# =========================================================================

if [ "$SKIP_BUILD" = true ]; then
    echo "[SKIP] Stage 1: Build"
else
    banner "Stage 1: Build"
    stage_start "build"

    cd "$PROJECT_DIR"
    mvn install -DskipTests

    stage_end "build"
fi

# =========================================================================
# Stage 2: Upload
# =========================================================================

if [ "$SKIP_UPLOAD" = true ]; then
    echo "[SKIP] Stage 2: Upload"
else
    banner "Stage 2: Upload to S3"
    stage_start "upload"

    UPLOAD_ARGS=()
    if [ -n "$COMPONENT" ]; then
        UPLOAD_NAME="${UPLOAD_MAP[$COMPONENT]:-}"
        if [ -z "$UPLOAD_NAME" ]; then
            echo "  Component '$COMPONENT' has no artifact to upload, skipping."
        else
            UPLOAD_ARGS+=("$UPLOAD_NAME")
        fi
    fi

    if [ -z "$COMPONENT" ] || [ -n "${UPLOAD_MAP[$COMPONENT]:-}" ]; then
        "$SCRIPT_DIR/upload-artifacts.sh" "${UPLOAD_ARGS[@]}"
    fi

    stage_end "upload"
fi

# =========================================================================
# Stage 3: Deploy
# =========================================================================

if [ "$SKIP_DEPLOY" = true ]; then
    echo "[SKIP] Stage 3: Deploy"
else
    banner "Stage 3: Deploy to Remote"
    stage_start "deploy"

    DEPLOY_ARGS=(-f "$TF_OUTPUTS" -i "$PEM_FILE" -u "$SSH_USER" --ssh-port "$SSH_PORT")
    if [ -n "$COMPONENT" ]; then
        DEPLOY_ARGS+=(--component "$COMPONENT")
    fi

    "$SCRIPT_DIR/setup-remote.sh" "${DEPLOY_ARGS[@]}"

    stage_end "deploy"
fi

# =========================================================================
# Stage 4: Restart
# =========================================================================

if [ "$SKIP_RESTART" = true ]; then
    echo "[SKIP] Stage 4: Restart"
else
    banner "Stage 4: Restart Services"
    stage_start "restart"

    MANAGE_ARGS=(-f "$TF_OUTPUTS" -i "$PEM_FILE" -u "$SSH_USER" --ssh-port "$SSH_PORT")

    if [ -n "$COMPONENT" ]; then
        "$SCRIPT_DIR/manage-services.sh" "${MANAGE_ARGS[@]}" restart "$COMPONENT"
    else
        "$SCRIPT_DIR/manage-services.sh" "${MANAGE_ARGS[@]}" restart
    fi

    stage_end "restart"

    echo "  Waiting ${RESTART_WAIT}s for services to stabilize..."
    sleep "$RESTART_WAIT"
fi

# =========================================================================
# Stage 5: Validate
# =========================================================================

banner "Stage 5: Validate"
stage_start "validate"

CHECKS_TOTAL=0
CHECKS_PASSED=0
CHECKS_FAILED=0
CHECKS_SKIPPED=0
VALIDATION_RESULTS=()

check_pass() {
    CHECKS_TOTAL=$((CHECKS_TOTAL + 1))
    CHECKS_PASSED=$((CHECKS_PASSED + 1))
    VALIDATION_RESULTS+=("  PASS  $1")
    echo "  PASS  $1"
}

check_fail() {
    CHECKS_TOTAL=$((CHECKS_TOTAL + 1))
    CHECKS_FAILED=$((CHECKS_FAILED + 1))
    VALIDATION_RESULTS+=("  FAIL  $1")
    echo "  FAIL  $1"
}

check_skip() {
    CHECKS_TOTAL=$((CHECKS_TOTAL + 1))
    CHECKS_SKIPPED=$((CHECKS_SKIPPED + 1))
    VALIDATION_RESULTS+=("  SKIP  $1")
    echo "  SKIP  $1"
}

should_check() {
    [ -z "$COMPONENT" ] || [ "$COMPONENT" = "$1" ]
}

# --- Check 1: Service status via manage-services.sh ---

echo ""
echo "--- Check 1: Service status ---"

if [ -n "$COMPONENT" ]; then
    SERVICES_TO_CHECK=("$COMPONENT")
else
    SERVICES_TO_CHECK=("${VALID_COMPONENTS[@]}")
fi

declare -A SVC_HOST SVC_JUMP SVC_NAME SVC_TYPE
SVC_HOST[aeron-store]="$AERON_IP";       SVC_JUMP[aeron-store]="jump";   SVC_NAME[aeron-store]="aeron-remote-store"; SVC_TYPE[aeron-store]="systemd"
SVC_HOST[exchange-simulator]="$FIX_IP";  SVC_JUMP[exchange-simulator]="jump";  SVC_NAME[exchange-simulator]="exchange-simulator"; SVC_TYPE[exchange-simulator]="systemd"
SVC_HOST[fix-initiator]="$OUCH_IP";     SVC_JUMP[fix-initiator]="jump"; SVC_NAME[fix-initiator]="fix-initiator"; SVC_TYPE[fix-initiator]="systemd"
SVC_HOST[omniview]="$MONITORING_IP";    SVC_JUMP[omniview]="direct";    SVC_NAME[omniview]="omniview"; SVC_TYPE[omniview]="systemd"
SVC_HOST[monitoring]="$MONITORING_IP";  SVC_JUMP[monitoring]="direct";   SVC_NAME[monitoring]=""; SVC_TYPE[monitoring]="docker"

for svc in "${SERVICES_TO_CHECK[@]}"; do
    host="${SVC_HOST[$svc]}"
    jump="${SVC_JUMP[$svc]}"
    svc_name="${SVC_NAME[$svc]}"
    svc_type="${SVC_TYPE[$svc]}"

    if [ "$svc_type" = "systemd" ]; then
        status_output=$(remote_exec "$host" "$jump" "sudo systemctl status $svc_name 2>&1" 2>/dev/null) || true
        if echo "$status_output" | grep -q "active (running)"; then
            check_pass "$svc: active (running)"
        else
            state=$(echo "$status_output" | grep -oP 'Active:\s+\K\S+ \([^)]+\)' 2>/dev/null || echo "unknown")
            check_fail "$svc: $state"
        fi
    elif [ "$svc_type" = "docker" ]; then
        docker_output=$(remote_exec "$host" "$jump" "cd /opt/monitoring && sudo docker compose ps --format '{{.Name}} {{.State}}' 2>/dev/null" 2>/dev/null) || true
        if [ -z "$docker_output" ]; then
            check_fail "monitoring: no containers running"
        else
            all_running=true
            while IFS= read -r line; do
                name=$(echo "$line" | awk '{print $1}')
                state=$(echo "$line" | awk '{print $2}')
                if [ "$state" != "running" ]; then
                    all_running=false
                fi
            done <<< "$docker_output"
            if [ "$all_running" = true ]; then
                check_pass "monitoring: all containers running"
            else
                check_fail "monitoring: some containers not running"
            fi
        fi
    fi
done

# --- Check 2: Journal errors (last 5 minutes) ---

echo ""
echo "--- Check 2: Journal errors (last 5 min) ---"

SYSTEMD_SERVICES=(aeron-store exchange-simulator fix-initiator omniview)

for svc in "${SYSTEMD_SERVICES[@]}"; do
    if ! should_check "$svc"; then continue; fi

    host="${SVC_HOST[$svc]}"
    jump="${SVC_JUMP[$svc]}"
    svc_name="${SVC_NAME[$svc]}"

    err_count=$(remote_exec "$host" "$jump" "journalctl -u $svc_name --since '5 min ago' -p err --no-pager -q 2>/dev/null | wc -l" 2>/dev/null) || err_count="?"
    err_count=$(echo "$err_count" | tr -d '[:space:]')

    if [ "$err_count" = "0" ]; then
        check_pass "$svc: 0 errors in journal"
    elif [ "$err_count" = "?" ]; then
        check_fail "$svc: could not read journal"
    else
        check_fail "$svc: $err_count error(s) in journal"
    fi
done

# --- Check 3: FIX logon (fix-initiator journal) ---

echo ""
echo "--- Check 3: FIX logon ---"

if should_check "fix-initiator"; then
    # Check for LOGGED_ON state in recent journal (covers logon at any point since boot)
    logon_state=$(remote_exec "$OUCH_IP" "jump" "journalctl -u fix-initiator --no-pager -q 2>/dev/null | grep -c 'LOGGED_ON'" 2>/dev/null) || logon_state="0"
    logon_state=$(echo "$logon_state" | tr -d '[:space:]')

    if [ "$logon_state" != "0" ] && [ -n "$logon_state" ]; then
        check_pass "fix-initiator: FIX logon detected (LOGGED_ON state seen $logon_state time(s))"
    else
        check_fail "fix-initiator: no FIX logon found in journal"
    fi
else
    check_skip "fix-initiator: FIX logon (not selected)"
fi

# --- Check 4: Simulator active sessions ---

echo ""
echo "--- Check 4: Simulator sessions ---"

if should_check "exchange-simulator"; then
    sessions_output=$(remote_exec "$FIX_IP" "jump" "curl -sf http://localhost:8080/api/sessions 2>/dev/null" 2>/dev/null) || sessions_output=""

    if [ -n "$sessions_output" ]; then
        # Check for at least one connected session
        connected=$(echo "$sessions_output" | grep -c '"connected" *: *true' 2>/dev/null) || connected="0"
        connected=$(echo "$connected" | tr -d '[:space:]')
        if [ "$connected" != "0" ]; then
            check_pass "exchange-simulator: $connected active session(s)"
        else
            check_fail "exchange-simulator: no connected sessions"
        fi
    else
        check_fail "exchange-simulator: sessions API not responding"
    fi
else
    check_skip "exchange-simulator: sessions (not selected)"
fi

# --- Check 5: OmniView health ---

echo ""
echo "--- Check 5: OmniView health ---"

if should_check "omniview"; then
    omniview_output=$(curl -sf "http://$MONITORING_IP:3000/api/apps" 2>/dev/null) || omniview_output=""

    if [ -n "$omniview_output" ]; then
        check_pass "omniview: /api/apps responding"
    else
        check_fail "omniview: /api/apps not responding (http://$MONITORING_IP:3000)"
    fi
else
    check_skip "omniview: health (not selected)"
fi

# --- Check 6: Monitoring stack ---

echo ""
echo "--- Check 6: Monitoring stack ---"

if should_check "monitoring"; then
    docker_ps=$(remote_exec "$MONITORING_IP" "direct" "cd /opt/monitoring && sudo docker compose ps --format '{{.Name}}:{{.State}}' 2>/dev/null" 2>/dev/null) || docker_ps=""

    if [ -z "$docker_ps" ]; then
        check_fail "monitoring: docker compose not responding"
    else
        expected_containers=("prometheus" "grafana" "alertmanager")
        all_ok=true
        for container in "${expected_containers[@]}"; do
            state=$(echo "$docker_ps" | grep "^${container}:" | cut -d: -f2 || echo "missing")
            if [ "$state" = "running" ]; then
                : # ok
            else
                all_ok=false
            fi
        done
        if [ "$all_ok" = true ]; then
            check_pass "monitoring: prometheus, grafana, alertmanager all running"
        else
            details=$(echo "$docker_ps" | tr '\n' ', ')
            check_fail "monitoring: some containers not healthy ($details)"
        fi
    fi
else
    check_skip "monitoring: stack (not selected)"
fi

stage_end "validate"

# =========================================================================
# Summary
# =========================================================================

PIPELINE_END=$(date +%s)
PIPELINE_ELAPSED=$((PIPELINE_END - PIPELINE_START))

echo ""
echo "================================================================"
echo "  Pipeline Summary"
echo "================================================================"
echo ""
echo "  Stages:"
for stage in build upload deploy restart validate; do
    elapsed="${STAGE_TIME[$stage]:-}"
    if [ -n "$elapsed" ]; then
        printf "    %-12s %ds\n" "$stage" "$elapsed"
    else
        printf "    %-12s skipped\n" "$stage"
    fi
done
echo ""
echo "  Total time: ${PIPELINE_ELAPSED}s"
echo ""
echo "  Validation:"
for r in "${VALIDATION_RESULTS[@]}"; do
    echo "  $r"
done
echo ""
echo "  Passed: $CHECKS_PASSED / $CHECKS_TOTAL"
if [ "$CHECKS_SKIPPED" -gt 0 ]; then
    echo "  Skipped: $CHECKS_SKIPPED"
fi
if [ "$CHECKS_FAILED" -gt 0 ]; then
    echo "  Failed: $CHECKS_FAILED"
fi
echo ""
echo "================================================================"

if [ "$CHECKS_FAILED" -gt 0 ]; then
    echo ""
    echo "Pipeline completed with $CHECKS_FAILED validation failure(s)."
    exit 1
else
    echo ""
    echo "Pipeline completed successfully."
fi
