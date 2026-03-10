#!/bin/bash
# =============================================================================
# Remote Setup Script
# =============================================================================
# Deploys OmniBridge applications to EC2 instances provisioned by Terraform.
# Reads instance IPs from the Terraform output JSON, downloads dist archives
# from S3 on each host, and writes production configuration files with the
# correct IP addresses.
#
# Usage: ./setup-remote.sh -f <tf-outputs.json> -i <pem-file> [options]
#
# Options:
#   -f, --tf-outputs   Terraform output JSON file (required)
#   -i, --identity     PEM file for SSH authentication (required)
#   -u, --user         SSH username (default: ubuntu)
#   -v, --version      App version (default: read from pom.xml)
#   -b, --bucket       S3 artifact bucket (default: omnibridge-artifacts)
#   -e, --environment  S3 environment prefix (default: production)
#   --component <name> Deploy only a specific component
#   --skip <name>      Skip a specific component (repeatable)
#   --ssh-port         SSH port (default: 22)
#
# Components: exchange-simulator, fix-initiator, aeron-store, omniview, monitoring
#
# Examples:
#   ./setup-remote.sh -f tf-outputs.json -i omnibridge-key.pem
#   ./setup-remote.sh -f tf-outputs.json -i key.pem -v 1.0.3-SNAPSHOT
#   ./setup-remote.sh -f tf-outputs.json -i key.pem --component exchange-simulator
#   ./setup-remote.sh -f tf-outputs.json -i key.pem --skip monitoring
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Defaults
SSH_USER="ubuntu"
SSH_PORT=22
VERSION=""
S3_BUCKET="omnibridge-artifacts"
ENVIRONMENT="production"
TF_OUTPUTS=""
PEM_FILE=""
ONLY_COMPONENT=""
SKIP_COMPONENTS=()

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        -f|--tf-outputs)  TF_OUTPUTS="$2";    shift 2 ;;
        -i|--identity)    PEM_FILE="$2";       shift 2 ;;
        -u|--user)        SSH_USER="$2";       shift 2 ;;
        -v|--version)     VERSION="$2";        shift 2 ;;
        -b|--bucket)      S3_BUCKET="$2";      shift 2 ;;
        -e|--environment) ENVIRONMENT="$2";    shift 2 ;;
        --component)      ONLY_COMPONENT="$2"; shift 2 ;;
        --skip)           SKIP_COMPONENTS+=("$2"); shift 2 ;;
        --ssh-port)       SSH_PORT="$2";       shift 2 ;;
        --help)
            echo "Remote Setup Script"
            echo ""
            echo "Usage: $0 -f <tf-outputs.json> -i <pem-file> [options]"
            echo ""
            echo "Options:"
            echo "  -f, --tf-outputs   Terraform output JSON file (required)"
            echo "  -i, --identity     PEM file for SSH authentication (required)"
            echo "  -u, --user         SSH username (default: ubuntu)"
            echo "  -v, --version      App version (default: read from pom.xml)"
            echo "  -b, --bucket       S3 artifact bucket (default: omnibridge-artifacts)"
            echo "  -e, --environment  S3 environment prefix (default: production)"
            echo "  --component <name> Deploy only a specific component"
            echo "  --skip <name>      Skip a specific component (repeatable)"
            echo "  --ssh-port         SSH port (default: 22)"
            echo ""
            echo "Components: exchange-simulator, fix-initiator, aeron-store, omniview, monitoring"
            exit 0
            ;;
        *)
            echo "Error: Unknown option: $1"
            exit 1
            ;;
    esac
done

# Validate required args
if [ -z "$TF_OUTPUTS" ]; then
    echo "Error: --tf-outputs is required"
    echo "Run '$0 --help' for usage."
    exit 1
fi
if [ -z "$PEM_FILE" ]; then
    echo "Error: --identity is required"
    echo "Run '$0 --help' for usage."
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

# Resolve PEM file to absolute path (needed for ProxyCommand subprocess)
PEM_FILE="$(cd "$(dirname "$PEM_FILE")" && pwd)/$(basename "$PEM_FILE")"

# Auto-detect version from pom.xml
if [ -z "$VERSION" ]; then
    VERSION=$(grep -m1 '<version>' "$SCRIPT_DIR/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
    if [ -z "$VERSION" ]; then
        echo "Error: Could not detect version. Use -v to specify."
        exit 1
    fi
fi

# -------------------------------------------------------------------------
# Extract IPs from Terraform outputs
# -------------------------------------------------------------------------

if ! command -v jq &> /dev/null; then
    echo "Error: jq is required. Install with: sudo apt install jq / brew install jq"
    exit 1
fi

FIX_IP=$(jq -r '.fix_acceptor_private_ip.value' "$TF_OUTPUTS" | tr -d '\r')
OUCH_IP=$(jq -r '.ouch_acceptor_private_ip.value' "$TF_OUTPUTS" | tr -d '\r')
AERON_IP=$(jq -r '.aeron_persistence_private_ip.value' "$TF_OUTPUTS" | tr -d '\r')
MONITORING_PUBLIC_IP=$(jq -r '.monitoring_public_ip.value' "$TF_OUTPUTS" | tr -d '\r')

if [ "$FIX_IP" = "null" ] || [ "$OUCH_IP" = "null" ] || [ "$AERON_IP" = "null" ] || [ "$MONITORING_PUBLIC_IP" = "null" ]; then
    echo "Error: Could not extract all IPs from $TF_OUTPUTS"
    echo "  FIX:        $FIX_IP"
    echo "  OUCH:       $OUCH_IP"
    echo "  Aeron:      $AERON_IP"
    echo "  Monitoring:  $MONITORING_PUBLIC_IP"
    exit 1
fi

S3_PREFIX="s3://$S3_BUCKET/omnibridge/$ENVIRONMENT"
SSH_OPTS="-i $PEM_FILE -o StrictHostKeyChecking=no -o ConnectTimeout=15 -p $SSH_PORT"
# All private-subnet hosts are reached via the monitoring instance as jump host.
# Use ProxyCommand (not ProxyJump) so the identity file is explicitly passed to the jump connection.
PROXY_CMD="ssh -i $PEM_FILE -o StrictHostKeyChecking=no -p $SSH_PORT -W %h:%p $SSH_USER@$MONITORING_PUBLIC_IP"

echo "========================================================"
echo "OmniBridge Remote Setup"
echo "========================================================"
echo "Version:       $VERSION"
echo "S3 Bucket:     $S3_BUCKET ($ENVIRONMENT)"
echo "PEM File:      $PEM_FILE"
echo "SSH User:      $SSH_USER"
echo ""
echo "Instance IPs:"
echo "  FIX Acceptor (exchange-simulator): $FIX_IP"
echo "  OUCH Acceptor (fix-initiator):     $OUCH_IP"
echo "  Aeron Persistence Store:           $AERON_IP"
echo "  Monitoring + OmniView (public):    $MONITORING_PUBLIC_IP"
echo "========================================================"
echo ""

# Pre-flight: verify SSH to the bastion (monitoring) host works
echo "Verifying SSH to bastion ($MONITORING_PUBLIC_IP)..."
if ! ssh $SSH_OPTS -o BatchMode=yes "$SSH_USER@$MONITORING_PUBLIC_IP" "true" 2>&1; then
    echo ""
    echo "ERROR: Cannot SSH to bastion host $MONITORING_PUBLIC_IP"
    echo "Check that:"
    echo "  1. Your public IP is in ssh_cidrs (run terraform-deploy.sh to auto-update)"
    echo "  2. The PEM file matches the EC2 key pair"
    echo "  3. The monitoring instance is running"
    exit 1
fi
echo "  Bastion OK"
echo ""

# Track results
RESULTS=()
FAILED=0

# Helper: check if component should be deployed
should_deploy() {
    local comp="$1"
    # Check --component filter
    if [ -n "$ONLY_COMPONENT" ] && [ "$ONLY_COMPONENT" != "$comp" ]; then
        return 1
    fi
    # Check --skip filter
    for skip in "${SKIP_COMPONENTS[@]}"; do
        if [ "$skip" = "$comp" ]; then
            return 1
        fi
    done
    return 0
}

# Helper: run remote command, log clearly
remote_exec() {
    local host="$1"
    local label="$2"
    local jump="$3"
    shift 3

    if [ "$jump" = "direct" ]; then
        ssh $SSH_OPTS "$SSH_USER@$host" "$@"
    else
        ssh $SSH_OPTS -o "ProxyCommand=$PROXY_CMD" "$SSH_USER@$host" "$@"
    fi
}

# Helper: ensure prerequisites are installed on a remote host (Ubuntu)
ensure_prerequisites() {
    local host="$1"
    local label="$2"
    local jump="$3"

    echo "  Installing prerequisites on $label..."
    remote_exec "$host" "$label" "$jump" << 'INSTALL_PREREQS'
set -e
NEED_UPDATE=false

# Check Java 17
if java -version 2>&1 | grep -q 'version "17'; then
    echo "    Java 17 already installed"
else
    NEED_UPDATE=true
fi

# Check AWS CLI
if command -v aws &>/dev/null; then
    echo "    AWS CLI already installed"
else
    NEED_UPDATE=true
fi

if [ "$NEED_UPDATE" = true ]; then
    echo "    Updating package index..."
    sudo apt-get update -qq
fi

# Install Java 17 (Amazon Corretto)
if ! java -version 2>&1 | grep -q 'version "17'; then
    echo "    Installing Java 17 (Amazon Corretto)..."
    # Import Corretto GPG key and repo
    if [ ! -f /usr/share/keyrings/corretto-keyring.gpg ]; then
        curl -sL https://apt.corretto.aws/corretto.key | sudo gpg --dearmor -o /usr/share/keyrings/corretto-keyring.gpg
        echo "deb [signed-by=/usr/share/keyrings/corretto-keyring.gpg] https://apt.corretto.aws stable main" | sudo tee /etc/apt/sources.list.d/corretto.list
        sudo apt-get update -qq
    fi
    sudo apt-get install -y -qq java-17-amazon-corretto-jdk 2>/dev/null
    echo "    Java installed: $(java -version 2>&1 | head -1)"
fi

# Install AWS CLI
if ! command -v aws &>/dev/null; then
    echo "    Installing AWS CLI..."
    sudo apt-get install -y -qq awscli 2>/dev/null
    echo "    AWS CLI installed: $(aws --version 2>&1)"
fi
INSTALL_PREREQS
}

# Helper: ensure Docker and Docker Compose are installed (Ubuntu)
ensure_docker() {
    local host="$1"
    local label="$2"
    local jump="$3"

    echo "  Installing Docker on $label..."
    remote_exec "$host" "$label" "$jump" << 'INSTALL_DOCKER'
set -e
if command -v docker &>/dev/null; then
    echo "    Docker already installed: $(docker --version)"
else
    echo "    Installing Docker..."
    sudo apt-get update -qq
    sudo apt-get install -y -qq ca-certificates curl gnupg
    sudo install -m 0755 -d /etc/apt/keyrings
    if [ ! -f /etc/apt/keyrings/docker.gpg ]; then
        curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
        sudo chmod a+r /etc/apt/keyrings/docker.gpg
        echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
        sudo apt-get update -qq
    fi
    sudo apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-compose-plugin 2>/dev/null
    sudo usermod -aG docker $USER
    echo "    Docker installed: $(docker --version)"
fi
INSTALL_DOCKER
}

# Helper: wait for SSH to become available
wait_for_ssh() {
    local host="$1"
    local label="$2"
    local jump="$3"
    local max_attempts=20
    local last_err=""

    echo "  Waiting for SSH on $label ($host)..."
    for i in $(seq 1 $max_attempts); do
        if [ "$jump" = "direct" ]; then
            last_err=$(ssh $SSH_OPTS -o BatchMode=yes "$SSH_USER@$host" "true" 2>&1) && return 0
        else
            last_err=$(ssh $SSH_OPTS -o "ProxyCommand=$PROXY_CMD" -o BatchMode=yes "$SSH_USER@$host" "true" 2>&1) && return 0
        fi
        if [ "$i" -eq 1 ]; then
            echo "    First attempt error: $last_err"
        fi
        echo "    Attempt $i/$max_attempts failed, retrying in 10s..."
        sleep 10
    done
    echo "  ERROR: SSH not available after ${max_attempts} attempts"
    echo "  Last error: $last_err"
    return 1
}

# =========================================================================
# Deploy: Exchange Simulator  (FIX acceptor instance)
# =========================================================================
deploy_exchange_simulator() {
    local HOST="$FIX_IP"
    local COMP="exchange-simulator"
    local DEPLOY_DIR="/opt/exchange-simulator"
    local DIST="exchange-simulator-${VERSION}-dist.tar.gz"

    echo "============================================================"
    echo "[$COMP] Deploying to $HOST"
    echo "============================================================"

    wait_for_ssh "$HOST" "$COMP" "jump" || { RESULTS+=("FAIL $COMP -> $HOST"); FAILED=$((FAILED+1)); return; }
    ensure_prerequisites "$HOST" "$COMP" "jump"

    remote_exec "$HOST" "$COMP" "jump" << REMOTE
set -e
echo "[$COMP] Downloading $DIST from S3..."
aws s3 cp "$S3_PREFIX/$DIST" "/tmp/$DIST"

echo "[$COMP] Extracting to $DEPLOY_DIR..."
sudo mkdir -p "$DEPLOY_DIR"
sudo chown $SSH_USER:$SSH_USER "$DEPLOY_DIR"
tar -xzf "/tmp/$DIST" -C "$DEPLOY_DIR" --strip-components=1
chmod +x "$DEPLOY_DIR/bin/"*.sh 2>/dev/null || true
rm -f "/tmp/$DIST"

echo "[$COMP] Writing Aeron persistence config overlay..."
cat > "$DEPLOY_DIR/conf/exchange-simulator-aeron.conf" << 'AERON_CONF'
include "exchange-simulator.conf"

demo {
    enabled = true
}

persistence {
    enabled = true
    store-type = "aeron"
    base-path = "$DEPLOY_DIR/data/local-cache"
    max-file-size = 256MB
    sync-on-write = false

    aeron {
        media-driver {
            embedded = true
            aeron-dir = "/dev/shm/aeron-simulator"
        }

        publisher-id = 1

        subscribers = [
            {
                name = "primary-remote-store"
                host = "$AERON_IP"
                data-port = 40456
                control-port = 40457
            }
        ]

        local-endpoint {
            host = "0.0.0.0"
            replay-port = 40458
        }

        replay {
            timeout-ms = 30000
            max-batch-size = 10000
        }

        heartbeat-interval-ms = 1000
        idle-strategy = "sleeping"
    }
}
AERON_CONF

# Substitute variables in the generated config (they are literal above)
sed -i "s|\\\$DEPLOY_DIR|$DEPLOY_DIR|g" "$DEPLOY_DIR/conf/exchange-simulator-aeron.conf"
sed -i "s|\\\$AERON_IP|$AERON_IP|g" "$DEPLOY_DIR/conf/exchange-simulator-aeron.conf"

echo "[$COMP] Creating systemd service..."
sudo tee /etc/systemd/system/exchange-simulator.service > /dev/null << SERVICE
[Unit]
Description=OmniBridge Exchange Simulator
After=network.target

[Service]
Type=simple
User=$SSH_USER
WorkingDirectory=$DEPLOY_DIR
ExecStart=/usr/bin/java \\
    -Xms2g -Xmx2g \\
    -XX:+UseZGC \\
    -XX:+AlwaysPreTouch \\
    --add-opens java.base/java.lang=ALL-UNNAMED \\
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED \\
    --add-opens java.base/java.io=ALL-UNNAMED \\
    --add-opens java.base/java.nio=ALL-UNNAMED \\
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED \\
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \\
    --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \\
    --add-exports java.base/jdk.internal.ref=ALL-UNNAMED \\
    --add-exports java.base/jdk.internal.util=ALL-UNNAMED \\
    --add-exports java.base/sun.nio.ch=ALL-UNNAMED \\
    -Dconfig.file=$DEPLOY_DIR/conf/exchange-simulator-aeron.conf \\
    -Dlogback.configurationFile=$DEPLOY_DIR/conf/logback.xml \\
    -Dadmin.port=8080 \\
    -cp '$DEPLOY_DIR/conf:$DEPLOY_DIR/lib/*' \\
    com.omnibridge.simulator.ExchangeSimulator
Restart=on-failure
RestartSec=5
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
SERVICE

sudo systemctl daemon-reload
sudo systemctl enable exchange-simulator

echo "[$COMP] Deployment complete on $HOST"
REMOTE

    if [ $? -eq 0 ]; then
        RESULTS+=("OK   $COMP -> $HOST")
        echo ""
    else
        RESULTS+=("FAIL $COMP -> $HOST")
        FAILED=$((FAILED+1))
        echo "[$COMP] ERROR: Deployment failed"
        echo ""
    fi
}

# =========================================================================
# Deploy: FIX Initiator  (OUCH acceptor instance, also runs FIX initiator)
# =========================================================================
deploy_fix_initiator() {
    local HOST="$OUCH_IP"
    local COMP="fix-initiator"
    local DEPLOY_DIR="/opt/fix-initiator"
    local DIST="fix-samples-${VERSION}-dist.tar.gz"

    echo "============================================================"
    echo "[$COMP] Deploying to $HOST"
    echo "============================================================"

    wait_for_ssh "$HOST" "$COMP" "jump" || { RESULTS+=("FAIL $COMP -> $HOST"); FAILED=$((FAILED+1)); return; }
    ensure_prerequisites "$HOST" "$COMP" "jump"

    remote_exec "$HOST" "$COMP" "jump" << REMOTE
set -e
echo "[$COMP] Downloading $DIST from S3..."
aws s3 cp "$S3_PREFIX/$DIST" "/tmp/$DIST"

echo "[$COMP] Extracting to $DEPLOY_DIR..."
sudo mkdir -p "$DEPLOY_DIR"
sudo chown $SSH_USER:$SSH_USER "$DEPLOY_DIR"
tar -xzf "/tmp/$DIST" -C "$DEPLOY_DIR" --strip-components=1
chmod +x "$DEPLOY_DIR/bin/"*.sh 2>/dev/null || true
rm -f "/tmp/$DIST"

echo "[$COMP] Writing initiator configuration..."
cat > "$DEPLOY_DIR/conf/initiator-aws.conf" << 'INIT_CONF'
include "reference.conf"
include "components.conf"

components {
    ouch-engine.enabled = false
}

network {
    name = "initiator-event-loop"
}

admin {
    port = 8082
}

demo {
    enabled = true
}

persistence {
    base-path = "$DEPLOY_DIR/data/fix-logs"
}

fix-engine {
    sessions = [
        {
            session-id = "INITIATOR_SESSION"
            port = 9876
            host = "$FIX_IP"
            sender-comp-id = "CLIENT1"
            target-comp-id = "EXCH1"
            initiator = true
            begin-string = "FIX.4.4"
            heartbeat-interval = 30
            max-reconnect-attempts = 10
        }
    ]
}
INIT_CONF

# Substitute variables in the generated config
sed -i "s|\\\$DEPLOY_DIR|$DEPLOY_DIR|g" "$DEPLOY_DIR/conf/initiator-aws.conf"
sed -i "s|\\\$FIX_IP|$FIX_IP|g" "$DEPLOY_DIR/conf/initiator-aws.conf"

echo "[$COMP] Creating systemd service..."
sudo tee /etc/systemd/system/fix-initiator.service > /dev/null << SERVICE
[Unit]
Description=OmniBridge FIX Initiator
After=network.target
Wants=network-online.target

[Service]
Type=simple
User=$SSH_USER
WorkingDirectory=$DEPLOY_DIR
ExecStart=/usr/bin/java \\
    -Xms512m -Xmx1g \\
    -XX:+UseZGC \\
    --add-opens java.base/java.lang=ALL-UNNAMED \\
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED \\
    --add-opens java.base/java.io=ALL-UNNAMED \\
    --add-opens java.base/java.nio=ALL-UNNAMED \\
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED \\
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \\
    --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \\
    --add-exports java.base/jdk.internal.ref=ALL-UNNAMED \\
    --add-exports java.base/jdk.internal.util=ALL-UNNAMED \\
    --add-exports java.base/sun.nio.ch=ALL-UNNAMED \\
    -Dlogback.configurationFile=$DEPLOY_DIR/conf/logback.xml \\
    -cp '$DEPLOY_DIR/conf:$DEPLOY_DIR/lib/*' \\
    com.omnibridge.apps.fix.initiator.SampleInitiator -c $DEPLOY_DIR/conf/initiator-aws.conf --demo --auto --count 10000 --rate 1
Restart=always
RestartSec=10
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
SERVICE

sudo systemctl daemon-reload
sudo systemctl enable fix-initiator

echo "[$COMP] Deployment complete on $HOST"
REMOTE

    if [ $? -eq 0 ]; then
        RESULTS+=("OK   $COMP -> $HOST")
        echo ""
    else
        RESULTS+=("FAIL $COMP -> $HOST")
        FAILED=$((FAILED+1))
        echo "[$COMP] ERROR: Deployment failed"
        echo ""
    fi
}

# =========================================================================
# Deploy: Aeron Remote Persistence Store
# =========================================================================
deploy_aeron_store() {
    local HOST="$AERON_IP"
    local COMP="aeron-store"
    local DEPLOY_DIR="/opt/aeron-store"
    local DIST="aeron-remote-store-${VERSION}-dist.tar.gz"

    echo "============================================================"
    echo "[$COMP] Deploying to $HOST"
    echo "============================================================"

    wait_for_ssh "$HOST" "$COMP" "jump" || { RESULTS+=("FAIL $COMP -> $HOST"); FAILED=$((FAILED+1)); return; }
    ensure_prerequisites "$HOST" "$COMP" "jump"

    remote_exec "$HOST" "$COMP" "jump" << REMOTE
set -e
echo "[$COMP] Downloading $DIST from S3..."
aws s3 cp "$S3_PREFIX/$DIST" "/tmp/$DIST"

echo "[$COMP] Extracting to $DEPLOY_DIR..."
sudo mkdir -p "$DEPLOY_DIR"
sudo chown $SSH_USER:$SSH_USER "$DEPLOY_DIR"
tar -xzf "/tmp/$DIST" -C "$DEPLOY_DIR" --strip-components=1
chmod +x "$DEPLOY_DIR/bin/"*.sh 2>/dev/null || true
rm -f "/tmp/$DIST"

echo "[$COMP] Writing remote store configuration..."
cat > "$DEPLOY_DIR/conf/aeron-remote-store.conf" << 'STORE_CONF'
aeron-remote-store {
    base-path = "$DEPLOY_DIR/data/remote-store"

    aeron {
        media-driver {
            embedded = true
            aeron-dir = "/dev/shm/aeron-remote-store"
        }

        listen {
            host = "0.0.0.0"
            data-port = 40456
            control-port = 40457
        }

        engines = [
            {
                name = "exchange-simulator"
                host = "$FIX_IP"
                replay-port = 40458
                publisher-id = 1
            }
        ]

        idle-strategy = "sleeping"
        fragment-limit = 256
    }
}

persistence {
    enabled = true
    store-type = "chronicle"
    base-path = "$DEPLOY_DIR/data/remote-store"
    max-file-size = 256MB
    sync-on-write = false
}

admin {
    port = 8083
    host = "0.0.0.0"
    context-path = "/api"
}

metrics {
    enabled = true
    include-jvm = true
}
STORE_CONF

# Substitute variables in the generated config
sed -i "s|\\\$DEPLOY_DIR|$DEPLOY_DIR|g" "$DEPLOY_DIR/conf/aeron-remote-store.conf"
sed -i "s|\\\$FIX_IP|$FIX_IP|g" "$DEPLOY_DIR/conf/aeron-remote-store.conf"

echo "[$COMP] Creating systemd service..."
sudo tee /etc/systemd/system/aeron-remote-store.service > /dev/null << SERVICE
[Unit]
Description=OmniBridge Aeron Remote Persistence Store
After=network.target

[Service]
Type=simple
User=$SSH_USER
WorkingDirectory=$DEPLOY_DIR
ExecStart=/usr/bin/java \\
    -Xms1g -Xmx2g \\
    -XX:+UseZGC \\
    -XX:+AlwaysPreTouch \\
    --add-opens java.base/java.lang=ALL-UNNAMED \\
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED \\
    --add-opens java.base/java.io=ALL-UNNAMED \\
    --add-opens java.base/java.nio=ALL-UNNAMED \\
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED \\
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \\
    --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \\
    --add-exports java.base/jdk.internal.ref=ALL-UNNAMED \\
    --add-exports java.base/sun.nio.ch=ALL-UNNAMED \\
    -Djava.net.preferIPv4Stack=true \\
    -cp '$DEPLOY_DIR/conf:$DEPLOY_DIR/lib/*' \\
    com.omnibridge.persistence.aeron.AeronRemoteStoreMain \\
    -c $DEPLOY_DIR/conf/aeron-remote-store.conf
Restart=on-failure
RestartSec=5
LimitNOFILE=65535
LimitMEMLOCK=infinity

[Install]
WantedBy=multi-user.target
SERVICE

sudo systemctl daemon-reload
sudo systemctl enable aeron-remote-store

echo "[$COMP] Deployment complete on $HOST"
REMOTE

    if [ $? -eq 0 ]; then
        RESULTS+=("OK   $COMP -> $HOST")
        echo ""
    else
        RESULTS+=("FAIL $COMP -> $HOST")
        FAILED=$((FAILED+1))
        echo "[$COMP] ERROR: Deployment failed"
        echo ""
    fi
}

# =========================================================================
# Deploy: OmniView  (monitoring instance)
# =========================================================================
deploy_omniview() {
    local HOST="$MONITORING_PUBLIC_IP"
    local COMP="omniview"
    local DEPLOY_DIR="/opt/omniview"
    local DIST="omniview-${VERSION}-dist.tar.gz"

    echo "============================================================"
    echo "[$COMP] Deploying to $HOST"
    echo "============================================================"

    wait_for_ssh "$HOST" "$COMP" "direct" || { RESULTS+=("FAIL $COMP -> $HOST"); FAILED=$((FAILED+1)); return; }
    ensure_prerequisites "$HOST" "$COMP" "direct"

    remote_exec "$HOST" "$COMP" "direct" << REMOTE
set -e
echo "[$COMP] Downloading $DIST from S3..."
aws s3 cp "$S3_PREFIX/$DIST" "/tmp/$DIST"

echo "[$COMP] Extracting to $DEPLOY_DIR..."
sudo mkdir -p "$DEPLOY_DIR"
sudo chown $SSH_USER:$SSH_USER "$DEPLOY_DIR"
tar -xzf "/tmp/$DIST" -C "$DEPLOY_DIR" --strip-components=1
chmod +x "$DEPLOY_DIR/bin/"*.sh 2>/dev/null || true
rm -f "/tmp/$DIST"

echo "[$COMP] Pre-configuring monitored applications..."
mkdir -p "$DEPLOY_DIR/data"
cat > "$DEPLOY_DIR/data/apps.json" << APPS_JSON
[
  {
    "id": "exch-sim",
    "name": "Exchange Simulator",
    "host": "$FIX_IP",
    "port": 8080,
    "enabled": true
  },
  {
    "id": "fix-init",
    "name": "FIX Initiator",
    "host": "$OUCH_IP",
    "port": 8082,
    "enabled": true
  }
]
APPS_JSON

echo "[$COMP] Creating systemd service..."
sudo tee /etc/systemd/system/omniview.service > /dev/null << SERVICE
[Unit]
Description=OmniBridge OmniView Protocol Monitor
After=network.target

[Service]
Type=simple
User=$SSH_USER
WorkingDirectory=$DEPLOY_DIR
ExecStart=/usr/bin/java -Xms128m -Xmx512m -Dport=3000 -Domniview.data.dir=$DEPLOY_DIR/data -jar $DEPLOY_DIR/lib/omniview.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
SERVICE

sudo systemctl daemon-reload
sudo systemctl enable omniview

echo "[$COMP] Deployment complete on $HOST"
REMOTE

    if [ $? -eq 0 ]; then
        RESULTS+=("OK   $COMP -> $HOST")
        echo ""
    else
        RESULTS+=("FAIL $COMP -> $HOST")
        FAILED=$((FAILED+1))
        echo "[$COMP] ERROR: Deployment failed"
        echo ""
    fi
}

# =========================================================================
# Deploy: Monitoring Stack  (monitoring instance — Docker Compose)
# =========================================================================
deploy_monitoring() {
    local HOST="$MONITORING_PUBLIC_IP"
    local COMP="monitoring"
    local DEPLOY_DIR="/opt/monitoring"

    echo "============================================================"
    echo "[$COMP] Deploying to $HOST"
    echo "============================================================"

    wait_for_ssh "$HOST" "$COMP" "direct" || { RESULTS+=("FAIL $COMP -> $HOST"); FAILED=$((FAILED+1)); return; }
    ensure_docker "$HOST" "$COMP" "direct"

    remote_exec "$HOST" "$COMP" "direct" << REMOTE
set -e
echo "[$COMP] Configuring Prometheus scrape targets..."

sudo mkdir -p "$DEPLOY_DIR/prometheus/rules" "$DEPLOY_DIR/grafana/provisioning/datasources" "$DEPLOY_DIR/grafana/provisioning/dashboards" "$DEPLOY_DIR/grafana/dashboards" "$DEPLOY_DIR/alertmanager"
sudo chown -R $SSH_USER:$SSH_USER "$DEPLOY_DIR"

cat > "$DEPLOY_DIR/prometheus/prometheus.yml" << 'PROM_CONF'
global:
  scrape_interval: 15s
  evaluation_interval: 15s

rule_files:
  - "rules/*.yml"

alerting:
  alertmanagers:
    - static_configs:
        - targets: ['alertmanager:9093']

scrape_configs:
  - job_name: 'exchange-simulator'
    metrics_path: '/api/metrics'
    static_configs:
      - targets: ['$FIX_IP:8080']
        labels:
          app: 'exchange-simulator'
          environment: 'production'

  - job_name: 'fix-initiator'
    metrics_path: '/api/metrics'
    static_configs:
      - targets: ['$OUCH_IP:8082']
        labels:
          app: 'fix-initiator'
          environment: 'production'

  - job_name: 'aeron-remote-store'
    metrics_path: '/api/metrics'
    static_configs:
      - targets: ['$AERON_IP:8083']
        labels:
          app: 'aeron-remote-store'
          environment: 'production'

  - job_name: 'prometheus'
    static_configs:
      - targets: ['localhost:9090']
PROM_CONF

sed -i "s|\\\$FIX_IP|$FIX_IP|g" "$DEPLOY_DIR/prometheus/prometheus.yml"
sed -i "s|\\\$OUCH_IP|$OUCH_IP|g" "$DEPLOY_DIR/prometheus/prometheus.yml"
sed -i "s|\\\$AERON_IP|$AERON_IP|g" "$DEPLOY_DIR/prometheus/prometheus.yml"

echo "[$COMP] Writing alert rules..."
cat > "$DEPLOY_DIR/prometheus/rules/omnibridge-alerts.yml" << 'ALERTS'
groups:
  - name: omnibridge
    rules:
      - alert: TradingInstanceDown
        expr: up == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Instance {{ \$labels.instance }} is down"

      - alert: SessionDisconnected
        expr: omnibridge_session_connected == 0
        for: 30s
        labels:
          severity: critical
        annotations:
          summary: "Session {{ \$labels.session }} is disconnected"

      - alert: HighFixLatency
        expr: omnibridge_message_latency_seconds{quantile="0.99"} > 0.001
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "FIX p99 latency above 1ms on {{ \$labels.instance }}"

      - alert: HighHeapUsage
        expr: jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.85
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Heap usage above 85% on {{ \$labels.instance }}"
ALERTS

echo "[$COMP] Writing Alertmanager config..."
cat > "$DEPLOY_DIR/alertmanager/alertmanager.yml" << 'AM_CONF'
global:
  resolve_timeout: 5m

route:
  receiver: 'default'
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h

receivers:
  - name: 'default'
AM_CONF

echo "[$COMP] Provisioning Grafana datasource and dashboard..."
mkdir -p "$DEPLOY_DIR/grafana/provisioning/datasources"
mkdir -p "$DEPLOY_DIR/grafana/provisioning/dashboards"
mkdir -p "$DEPLOY_DIR/grafana/dashboards"

cat > "$DEPLOY_DIR/grafana/provisioning/datasources/prometheus.yml" << 'DS_CONF'
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    uid: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: false
DS_CONF

cat > "$DEPLOY_DIR/grafana/provisioning/dashboards/dashboards.yml" << 'DB_PROV'
apiVersion: 1

providers:
  - name: OmniBridge
    orgId: 1
    folder: ''
    type: file
    disableDeletion: false
    editable: true
    updateIntervalSeconds: 30
    options:
      path: /var/lib/grafana/dashboards
      foldersFromFilesStructure: false
DB_PROV

cat > "$DEPLOY_DIR/grafana/dashboards/omnibridge.json" << 'DASHBOARD_JSON'
{
  "annotations": { "list": [] },
  "editable": true,
  "fiscalYearStartMonth": 0,
  "graphTooltip": 1,
  "links": [],
  "panels": [
    {
      "title": "Service Up/Down",
      "type": "stat",
      "gridPos": { "h": 4, "w": 12, "x": 0, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": {
        "defaults": {
          "mappings": [
            { "options": { "0": { "color": "red", "text": "DOWN" }, "1": { "color": "green", "text": "UP" } }, "type": "value" }
          ],
          "thresholds": { "mode": "absolute", "steps": [{ "color": "red", "value": null }, { "color": "green", "value": 1 }] }
        },
        "overrides": []
      },
      "options": { "colorMode": "background", "graphMode": "none", "justifyMode": "auto", "textMode": "auto", "reduceOptions": { "calcs": ["lastNotNull"] } },
      "targets": [{ "expr": "up{job=~\"exchange-simulator|fix-initiator\"}", "legendFormat": "{{job}}", "refId": "A" }]
    },
    {
      "title": "Uptime",
      "type": "stat",
      "gridPos": { "h": 4, "w": 12, "x": 12, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "unit": "s", "thresholds": { "mode": "absolute", "steps": [{ "color": "green", "value": null }] } }, "overrides": [] },
      "options": { "colorMode": "value", "graphMode": "none", "reduceOptions": { "calcs": ["lastNotNull"] } },
      "targets": [{ "expr": "process_uptime_seconds{job=~\"exchange-simulator|fix-initiator\"}", "legendFormat": "{{job}}", "refId": "A" }]
    },
    {
      "title": "Process CPU Usage",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 4 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "unit": "percentunit", "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2 } }, "overrides": [] },
      "targets": [{ "expr": "process_cpu_usage{job=~\"exchange-simulator|fix-initiator\"}", "legendFormat": "{{job}}", "refId": "A" }]
    },
    {
      "title": "System CPU Usage",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 4 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "unit": "percentunit", "min": 0, "max": 1, "custom": { "fillOpacity": 10, "lineWidth": 2 } }, "overrides": [] },
      "targets": [{ "expr": "system_cpu_usage{job=~\"exchange-simulator|fix-initiator\"}", "legendFormat": "{{job}}", "refId": "A" }]
    },
    {
      "title": "Heap Memory Used",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 12 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "unit": "bytes", "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2 } }, "overrides": [] },
      "targets": [
        { "expr": "jvm_memory_used_bytes{area=\"heap\",job=~\"exchange-simulator|fix-initiator\"}", "legendFormat": "{{job}} used", "refId": "A" },
        { "expr": "jvm_memory_max_bytes{area=\"heap\",job=~\"exchange-simulator|fix-initiator\"}", "legendFormat": "{{job}} max", "refId": "B" }
      ]
    },
    {
      "title": "Heap Usage %",
      "type": "gauge",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 12 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": {
        "defaults": {
          "unit": "percentunit", "min": 0, "max": 1,
          "thresholds": { "mode": "absolute", "steps": [{ "color": "green", "value": null }, { "color": "yellow", "value": 0.7 }, { "color": "red", "value": 0.85 }] }
        },
        "overrides": []
      },
      "options": { "reduceOptions": { "calcs": ["lastNotNull"] } },
      "targets": [{ "expr": "jvm_memory_used_bytes{area=\"heap\",job=~\"exchange-simulator|fix-initiator\"} / jvm_memory_max_bytes{area=\"heap\",job=~\"exchange-simulator|fix-initiator\"}", "legendFormat": "{{job}}", "refId": "A" }]
    },
    {
      "title": "Live Threads",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 20 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2 } }, "overrides": [] },
      "targets": [{ "expr": "jvm_threads_live_threads{job=~\"exchange-simulator|fix-initiator\"}", "legendFormat": "{{job}}", "refId": "A" }]
    },
    {
      "title": "Thread States",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 20 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2, "stacking": { "mode": "normal" } } }, "overrides": [] },
      "targets": [{ "expr": "jvm_threads_states_threads{job=~\"exchange-simulator|fix-initiator\"}", "legendFormat": "{{job}} {{state}}", "refId": "A" }]
    },
    {
      "title": "GC Memory Allocated Rate",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 28 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "unit": "Bps", "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2 } }, "overrides": [] },
      "targets": [{ "expr": "rate(jvm_gc_memory_allocated_bytes_total{job=~\"exchange-simulator|fix-initiator\"}[1m])", "legendFormat": "{{job}}", "refId": "A" }]
    },
    {
      "title": "GC Pause Duration",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 28 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "unit": "s", "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2 } }, "overrides": [] },
      "targets": [
        { "expr": "rate(jvm_gc_pause_seconds_sum{job=~\"exchange-simulator|fix-initiator\"}[1m])", "legendFormat": "{{job}} pause", "refId": "A" },
        { "expr": "rate(jvm_gc_concurrent_phase_time_seconds_sum{job=~\"exchange-simulator|fix-initiator\"}[1m])", "legendFormat": "{{job}} concurrent", "refId": "B" }
      ]
    },
    {
      "title": "Direct Buffers",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 36 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2 } }, "overrides": [] },
      "targets": [{ "expr": "jvm_buffer_count_buffers{id=\"direct\",job=~\"exchange-simulator|fix-initiator\"}", "legendFormat": "{{job}}", "refId": "A" }]
    },
    {
      "title": "Direct Buffer Memory",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 36 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "unit": "bytes", "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2 } }, "overrides": [] },
      "targets": [{ "expr": "jvm_buffer_memory_used_bytes{id=\"direct\",job=~\"exchange-simulator|fix-initiator\"}", "legendFormat": "{{job}}", "refId": "A" }]
    },
    {
      "title": "System Load Average (1m)",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 24, "x": 0, "y": 44 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2 } }, "overrides": [] },
      "targets": [{ "expr": "system_load_average_1m{job=~\"exchange-simulator|fix-initiator\"}", "legendFormat": "{{job}}", "refId": "A" }]
    }
  ],
  "refresh": "10s",
  "schemaVersion": 39,
  "tags": ["omnibridge", "fix", "trading"],
  "templating": { "list": [] },
  "time": { "from": "now-1h", "to": "now" },
  "timepicker": {},
  "timezone": "browser",
  "title": "OmniBridge",
  "uid": "omnibridge-overview",
  "version": 1
}
DASHBOARD_JSON

cat > "$DEPLOY_DIR/grafana/dashboards/fix-engine.json" << 'FIX_DASHBOARD'
{
  "annotations": { "list": [] },
  "editable": true,
  "fiscalYearStartMonth": 0,
  "graphTooltip": 1,
  "links": [],
  "templating": {
    "list": [
      {
        "current": { "selected": true, "text": "All", "value": "\$__all" },
        "datasource": { "type": "prometheus", "uid": "prometheus" },
        "definition": "label_values(omnibridge_session_state, job)",
        "allValue": ".*",
        "includeAll": true,
        "multi": true,
        "name": "job",
        "query": "label_values(omnibridge_session_state, job)",
        "refresh": 2,
        "sort": 1,
        "type": "query"
      },
      {
        "current": { "selected": true, "text": "All", "value": "\$__all" },
        "datasource": { "type": "prometheus", "uid": "prometheus" },
        "definition": "label_values(omnibridge_session_state{job=~\"\$job\"}, session_id)",
        "allValue": ".*",
        "includeAll": true,
        "multi": true,
        "name": "session_id",
        "query": "label_values(omnibridge_session_state{job=~\"\$job\"}, session_id)",
        "refresh": 2,
        "sort": 1,
        "type": "query"
      }
    ]
  },
  "panels": [
    {
      "title": "Session Details",
      "type": "table",
      "gridPos": { "h": 10, "w": 24, "x": 0, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": {
        "defaults": { "custom": { "align": "auto", "cellOptions": { "type": "auto" }, "filterable": true } },
        "overrides": [
          { "matcher": { "id": "byName", "options": "State" }, "properties": [
            { "id": "mappings", "value": [
              { "type": "value", "options": {
                "0": { "text": "CREATED", "color": "text" },
                "1": { "text": "DISCONNECTED", "color": "red" },
                "2": { "text": "CONNECTING", "color": "yellow" },
                "3": { "text": "CONNECTED", "color": "orange" },
                "4": { "text": "LOGON_SENT", "color": "orange" },
                "5": { "text": "LOGGED_ON", "color": "green" },
                "6": { "text": "RESENDING", "color": "blue" },
                "7": { "text": "LOGOUT_SENT", "color": "yellow" },
                "8": { "text": "STOPPED", "color": "red" }
              }}
            ]},
            { "id": "custom.cellOptions", "value": { "type": "color-text" } }
          ]},
          { "matcher": { "id": "byName", "options": "Last Recv (s)" }, "properties": [
            { "id": "unit", "value": "s" },
            { "id": "decimals", "value": 1 },
            { "id": "thresholds", "value": { "mode": "absolute", "steps": [{ "color": "green", "value": null }, { "color": "yellow", "value": 35 }, { "color": "red", "value": 65 }] } },
            { "id": "custom.cellOptions", "value": { "type": "color-text" } }
          ]},
          { "matcher": { "id": "byName", "options": "Msgs Sent" }, "properties": [{ "id": "decimals", "value": 0 }] },
          { "matcher": { "id": "byName", "options": "Msgs Recv" }, "properties": [{ "id": "decimals", "value": 0 }] },
          { "matcher": { "id": "byName", "options": "Out SeqNum" }, "properties": [{ "id": "decimals", "value": 0 }] },
          { "matcher": { "id": "byName", "options": "In SeqNum" }, "properties": [{ "id": "decimals", "value": 0 }] }
        ]
      },
      "options": { "showHeader": true, "sortBy": [{ "displayName": "Session", "desc": false }], "footer": { "show": false } },
      "targets": [
        { "expr": "omnibridge_session_state{job=~\"\$job\",session_id=~\"\$session_id\"}", "format": "table", "instant": true, "refId": "A" },
        { "expr": "omnibridge_sequence_outgoing{job=~\"\$job\",session_id=~\"\$session_id\"}", "format": "table", "instant": true, "refId": "B" },
        { "expr": "omnibridge_sequence_incoming_expected{job=~\"\$job\",session_id=~\"\$session_id\"}", "format": "table", "instant": true, "refId": "C" },
        { "expr": "omnibridge_messages_sent_total{job=~\"\$job\",session_id=~\"\$session_id\"}", "format": "table", "instant": true, "refId": "D" },
        { "expr": "omnibridge_messages_received_total{job=~\"\$job\",session_id=~\"\$session_id\"}", "format": "table", "instant": true, "refId": "E" },
        { "expr": "omnibridge_heartbeat_last_received_seconds{job=~\"\$job\",session_id=~\"\$session_id\"}", "format": "table", "instant": true, "refId": "F" }
      ],
      "transformations": [
        { "id": "filterFieldsByName", "options": { "include": { "pattern": "^(?!__name__|Time|instance|app|environment)" } } },
        { "id": "merge", "options": {} },
        { "id": "organize", "options": {
          "renameByName": { "session_id": "Session", "job": "App", "protocol": "Protocol", "role": "Role", "Value #A": "State", "Value #B": "Out SeqNum", "Value #C": "In SeqNum", "Value #D": "Msgs Sent", "Value #E": "Msgs Recv", "Value #F": "Last Recv (s)" },
          "indexByName": { "Session": 0, "App": 1, "Protocol": 2, "Role": 3, "State": 4, "Out SeqNum": 5, "In SeqNum": 6, "Msgs Sent": 7, "Msgs Recv": 8, "Last Recv (s)": 9 }
        }}
      ]
    },
    {
      "title": "Session Status",
      "type": "stat",
      "gridPos": { "h": 4, "w": 8, "x": 0, "y": 10 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": {
        "defaults": {
          "mappings": [
            { "options": { "0": { "color": "red", "text": "OFF" }, "1": { "color": "green", "text": "ON" } }, "type": "value" }
          ],
          "thresholds": { "mode": "absolute", "steps": [{ "color": "red", "value": null }, { "color": "green", "value": 1 }] }
        },
        "overrides": []
      },
      "options": { "colorMode": "background", "graphMode": "none", "justifyMode": "auto", "textMode": "auto", "reduceOptions": { "calcs": ["lastNotNull"] } },
      "targets": [{ "expr": "omnibridge_session_logged_on{job=~\"\$job\",session_id=~\"\$session_id\"}", "legendFormat": "{{session_id}}", "refId": "A" }]
    },
    {
      "title": "Aggregate Sessions",
      "type": "stat",
      "gridPos": { "h": 4, "w": 8, "x": 8, "y": 10 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "thresholds": { "mode": "absolute", "steps": [{ "color": "blue", "value": null }] } }, "overrides": [] },
      "options": { "colorMode": "value", "graphMode": "none", "reduceOptions": { "calcs": ["lastNotNull"] } },
      "targets": [
        { "expr": "omnibridge_session_total{job=~\"\$job\"}", "legendFormat": "{{job}} total", "refId": "A" },
        { "expr": "omnibridge_session_connected_count{job=~\"\$job\"}", "legendFormat": "{{job}} connected", "refId": "B" },
        { "expr": "omnibridge_session_logged_on_count{job=~\"\$job\"}", "legendFormat": "{{job}} logged on", "refId": "C" }
      ]
    },
    {
      "title": "Processing Latency p99 (ns)",
      "type": "stat",
      "gridPos": { "h": 4, "w": 8, "x": 16, "y": 10 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": {
        "defaults": {
          "unit": "ns",
          "thresholds": { "mode": "absolute", "steps": [{ "color": "green", "value": null }, { "color": "yellow", "value": 100000 }, { "color": "red", "value": 1000000 }] }
        },
        "overrides": []
      },
      "options": { "colorMode": "value", "graphMode": "area", "reduceOptions": { "calcs": ["lastNotNull"] } },
      "targets": [{ "expr": "omnibridge_message_processing_time{job=~\"\$job\",session_id=~\"\$session_id\",quantile=\"0.99\"}", "legendFormat": "{{session_id}}", "refId": "A" }]
    },
    {
      "title": "Messages Sent Rate",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 14 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "unit": "ops", "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2, "showPoints": "never" } }, "overrides": [] },
      "targets": [{ "expr": "rate(omnibridge_messages_sent_total{job=~\"\$job\",session_id=~\"\$session_id\"}[1m])", "legendFormat": "{{job}} / {{session_id}}", "refId": "A" }]
    },
    {
      "title": "Messages Received Rate",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 14 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "unit": "ops", "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2, "showPoints": "never" } }, "overrides": [] },
      "targets": [{ "expr": "rate(omnibridge_messages_received_total{job=~\"\$job\",session_id=~\"\$session_id\"}[1m])", "legendFormat": "{{job}} / {{session_id}}", "refId": "A" }]
    },
    {
      "title": "Message Processing Latency",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 24, "x": 0, "y": 22 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "unit": "ns", "min": 0, "custom": { "fillOpacity": 5, "lineWidth": 2, "showPoints": "never" } }, "overrides": [] },
      "targets": [
        { "expr": "omnibridge_message_processing_time{job=~\"\$job\",session_id=~\"\$session_id\",quantile=\"0.5\"}", "legendFormat": "{{session_id}} p50", "refId": "A" },
        { "expr": "omnibridge_message_processing_time{job=~\"\$job\",session_id=~\"\$session_id\",quantile=\"0.9\"}", "legendFormat": "{{session_id}} p90", "refId": "B" },
        { "expr": "omnibridge_message_processing_time{job=~\"\$job\",session_id=~\"\$session_id\",quantile=\"0.95\"}", "legendFormat": "{{session_id}} p95", "refId": "C" },
        { "expr": "omnibridge_message_processing_time{job=~\"\$job\",session_id=~\"\$session_id\",quantile=\"0.99\"}", "legendFormat": "{{session_id}} p99", "refId": "D" },
        { "expr": "omnibridge_message_processing_time{job=~\"\$job\",session_id=~\"\$session_id\",quantile=\"0.999\"}", "legendFormat": "{{session_id}} p99.9", "refId": "E" }
      ]
    },
    {
      "title": "Heartbeat Rate",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 30 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "unit": "ops", "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2, "showPoints": "never" } }, "overrides": [] },
      "targets": [
        { "expr": "rate(omnibridge_heartbeat_sent_total{job=~\"\$job\",session_id=~\"\$session_id\"}[1m])", "legendFormat": "{{session_id}} sent", "refId": "A" },
        { "expr": "rate(omnibridge_heartbeat_received_total{job=~\"\$job\",session_id=~\"\$session_id\"}[1m])", "legendFormat": "{{session_id}} received", "refId": "B" }
      ]
    },
    {
      "title": "Heartbeat Timeouts & Test Requests",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 30 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2, "showPoints": "never" } }, "overrides": [] },
      "targets": [
        { "expr": "increase(omnibridge_heartbeat_timeout_total{job=~\"\$job\",session_id=~\"\$session_id\"}[5m])", "legendFormat": "{{session_id}} timeouts", "refId": "A" },
        { "expr": "increase(omnibridge_heartbeat_test_request_sent_total{job=~\"\$job\",session_id=~\"\$session_id\"}[5m])", "legendFormat": "{{session_id}} test req sent", "refId": "B" },
        { "expr": "increase(omnibridge_heartbeat_test_request_received_total{job=~\"\$job\",session_id=~\"\$session_id\"}[5m])", "legendFormat": "{{session_id}} test req recv", "refId": "C" }
      ]
    },
    {
      "title": "Outgoing Sequence Number",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 38 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2, "showPoints": "never" } }, "overrides": [] },
      "targets": [{ "expr": "omnibridge_sequence_outgoing{job=~\"\$job\",session_id=~\"\$session_id\"}", "legendFormat": "{{job}} / {{session_id}}", "refId": "A" }]
    },
    {
      "title": "Expected Incoming Sequence Number",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 38 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2, "showPoints": "never" } }, "overrides": [] },
      "targets": [{ "expr": "omnibridge_sequence_incoming_expected{job=~\"\$job\",session_id=~\"\$session_id\"}", "legendFormat": "{{job}} / {{session_id}}", "refId": "A" }]
    },
    {
      "title": "Session Lifecycle Events (5m)",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 46 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2, "drawStyle": "bars", "showPoints": "never" } }, "overrides": [] },
      "targets": [
        { "expr": "increase(omnibridge_session_logon_total{job=~\"\$job\",session_id=~\"\$session_id\"}[5m])", "legendFormat": "{{session_id}} logon", "refId": "A" },
        { "expr": "increase(omnibridge_session_logout_total{job=~\"\$job\",session_id=~\"\$session_id\"}[5m])", "legendFormat": "{{session_id}} logout", "refId": "B" },
        { "expr": "increase(omnibridge_session_disconnect_total{job=~\"\$job\",session_id=~\"\$session_id\"}[5m])", "legendFormat": "{{session_id}} disconnect", "refId": "C" },
        { "expr": "increase(omnibridge_session_connect_failed_total{job=~\"\$job\",session_id=~\"\$session_id\"}[5m])", "legendFormat": "{{session_id}} connect fail", "refId": "D" }
      ]
    },
    {
      "title": "Message Rejects (5m)",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 46 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2, "drawStyle": "bars", "showPoints": "never" } }, "overrides": [] },
      "targets": [
        { "expr": "increase(omnibridge_messages_rejected_total{job=~\"\$job\",session_id=~\"\$session_id\"}[5m])", "legendFormat": "{{session_id}} session reject", "refId": "A" },
        { "expr": "increase(omnibridge_messages_business_rejected_total{job=~\"\$job\",session_id=~\"\$session_id\"}[5m])", "legendFormat": "{{session_id}} business reject", "refId": "B" }
      ]
    },
    {
      "title": "Resend Requests (5m)",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 54 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2, "drawStyle": "bars", "showPoints": "never" } }, "overrides": [] },
      "targets": [
        { "expr": "increase(omnibridge_resend_request_total{job=~\"\$job\",session_id=~\"\$session_id\",direction=\"sent\"}[5m])", "legendFormat": "{{session_id}} sent", "refId": "A" },
        { "expr": "increase(omnibridge_resend_request_total{job=~\"\$job\",session_id=~\"\$session_id\",direction=\"received\"}[5m])", "legendFormat": "{{session_id}} received", "refId": "B" }
      ]
    },
    {
      "title": "Sequence Gaps & Resets (5m)",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 54 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2, "drawStyle": "bars", "showPoints": "never" } }, "overrides": [] },
      "targets": [
        { "expr": "increase(omnibridge_sequence_gap_total{job=~\"\$job\",session_id=~\"\$session_id\"}[5m])", "legendFormat": "{{session_id}} gap", "refId": "A" },
        { "expr": "increase(omnibridge_sequence_reset_total{job=~\"\$job\",session_id=~\"\$session_id\"}[5m])", "legendFormat": "{{session_id}} reset", "refId": "B" },
        { "expr": "increase(omnibridge_ringbuffer_claim_failed_total{job=~\"\$job\",session_id=~\"\$session_id\"}[5m])", "legendFormat": "{{session_id}} ringbuf fail", "refId": "C" }
      ]
    },
    {
      "title": "Seconds Since Last Message Received",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 62 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "unit": "s", "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2, "showPoints": "never" } }, "overrides": [] },
      "targets": [
        { "expr": "omnibridge_heartbeat_last_received_seconds{job=~\"\$job\",session_id=~\"\$session_id\"}", "legendFormat": "{{session_id}}", "refId": "A" },
        { "expr": "omnibridge_heartbeat_interval_seconds{job=~\"\$job\",session_id=~\"\$session_id\"}", "legendFormat": "{{session_id}} interval", "refId": "B" }
      ]
    },
    {
      "title": "Messages Sent / Received (Total)",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 62 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "min": 0, "custom": { "fillOpacity": 10, "lineWidth": 2, "showPoints": "never" } }, "overrides": [] },
      "targets": [
        { "expr": "omnibridge_messages_sent_total{job=~\"\$job\",session_id=~\"\$session_id\"}", "legendFormat": "{{session_id}} sent", "refId": "A" },
        { "expr": "omnibridge_messages_received_total{job=~\"\$job\",session_id=~\"\$session_id\"}", "legendFormat": "{{session_id}} received", "refId": "B" }
      ]
    }
  ],
  "refresh": "10s",
  "schemaVersion": 39,
  "tags": ["omnibridge", "fix", "sessions", "trading"],
  "time": { "from": "now-1h", "to": "now" },
  "timepicker": {},
  "timezone": "browser",
  "title": "FIX Engine",
  "uid": "fix-engine-sessions",
  "version": 1
}
FIX_DASHBOARD

echo "[$COMP] Writing Docker Compose file..."
cat > "$DEPLOY_DIR/docker-compose.yml" << 'COMPOSE'
version: '3.8'

services:
  prometheus:
    image: prom/prometheus:v2.48.0
    container_name: prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
      - ./prometheus/rules:/etc/prometheus/rules
      - prometheus-data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.retention.time=30d'
      - '--web.enable-lifecycle'
    restart: unless-stopped

  grafana:
    image: grafana/grafana:10.2.2
    container_name: grafana
    ports:
      - "3001:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
      - GF_USERS_ALLOW_SIGN_UP=false
    volumes:
      - grafana-data:/var/lib/grafana
      - ./grafana/provisioning:/etc/grafana/provisioning
      - ./grafana/dashboards:/var/lib/grafana/dashboards
    restart: unless-stopped

  alertmanager:
    image: prom/alertmanager:v0.26.0
    container_name: alertmanager
    ports:
      - "9093:9093"
    volumes:
      - ./alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml
    restart: unless-stopped

volumes:
  prometheus-data:
  grafana-data:
COMPOSE

echo "[$COMP] Starting Docker Compose stack..."
cd "$DEPLOY_DIR"
if command -v docker &> /dev/null; then
    sudo docker compose up -d
    echo "[$COMP] Monitoring stack started"
else
    echo "[$COMP] WARNING: Docker not available. Install Docker and run: cd $DEPLOY_DIR && sudo docker compose up -d"
fi

echo "[$COMP] Deployment complete on $HOST"
REMOTE

    if [ $? -eq 0 ]; then
        RESULTS+=("OK   $COMP -> $HOST")
        echo ""
    else
        RESULTS+=("FAIL $COMP -> $HOST")
        FAILED=$((FAILED+1))
        echo "[$COMP] ERROR: Deployment failed"
        echo ""
    fi
}

# =========================================================================
# Execute deployments in dependency order
# =========================================================================

# 1. Aeron Remote Store (no dependencies)
should_deploy "aeron-store" && deploy_aeron_store

# 2. Exchange Simulator (depends on Aeron Store IP)
should_deploy "exchange-simulator" && deploy_exchange_simulator

# 3. FIX Initiator (depends on Exchange Simulator IP)
should_deploy "fix-initiator" && deploy_fix_initiator

# 4. OmniView (depends on all admin IPs)
should_deploy "omniview" && deploy_omniview

# 5. Monitoring (depends on all admin IPs)
should_deploy "monitoring" && deploy_monitoring

# =========================================================================
# Summary
# =========================================================================
echo ""
echo "========================================================"
echo "Deployment Summary"
echo "========================================================"
for result in "${RESULTS[@]}"; do
    echo "  $result"
done
echo "========================================================"
echo ""

if [ $FAILED -gt 0 ]; then
    echo "$FAILED component(s) failed. Check logs above."
    echo ""
    exit 1
fi

echo "All components deployed successfully."
echo ""
echo "Next steps:"
echo "  1. Start all services:  ./scripts/deploy/manage-services.sh -f $TF_OUTPUTS -i $PEM_FILE start"
echo "  2. Check status:        ./scripts/deploy/manage-services.sh -f $TF_OUTPUTS -i $PEM_FILE status"
echo ""
echo "Endpoints:"
echo "  Grafana:   http://$MONITORING_PUBLIC_IP:3001"
echo "  OmniView:  http://$MONITORING_PUBLIC_IP:3000"
echo "  FIX:       $FIX_IP:9876  (via SSH jump through $MONITORING_PUBLIC_IP)"
