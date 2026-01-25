#!/bin/bash
#
# Deploy Sample FIX and OUCH Acceptors to a remote Linux server
#
# Usage: ./deploy-samples.sh -i <pem-file> -u <username> -h <hostname> [options]
#
# This script:
#   1. Copies FIX and OUCH sample distributions to the remote server
#   2. Extracts and installs both applications
#   3. Restarts the FIX and OUCH acceptors
#
# Ports used:
#   FIX Acceptor:  9876 (FIX protocol), 8081 (Admin API)
#   OUCH Acceptor: 9200 (OUCH protocol), 8082 (Admin API)
#

set -e

# Default values
DEPLOY_DIR="/opt/samples"
SSH_PORT=22
FIX_ADMIN_PORT=8081
OUCH_ADMIN_PORT=8082

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

usage() {
    echo "Usage: $0 -i <pem-file> -u <username> -h <hostname> [options]"
    echo ""
    echo "Required arguments:"
    echo "  -i, --identity <pem-file>   Path to the PEM file for SSH authentication"
    echo "  -u, --user <username>       SSH username"
    echo "  -h, --host <hostname>       Remote hostname or IP address"
    echo ""
    echo "Optional arguments:"
    echo "  -d, --deploy-dir <dir>      Deployment directory (default: /opt/samples)"
    echo "  -s, --ssh-port <port>       SSH port (default: 22)"
    echo "  --fix-only                  Deploy only FIX acceptor"
    echo "  --ouch-only                 Deploy only OUCH acceptor"
    echo "  --help                      Show this help message"
    echo ""
    echo "Port assignments:"
    echo "  FIX Acceptor:  9876 (FIX), 8081 (Admin)"
    echo "  OUCH Acceptor: 9200 (OUCH), 8082 (Admin)"
    echo ""
    echo "Examples:"
    echo "  $0 -i ~/.ssh/mykey.pem -u ubuntu -h 192.168.1.100"
    echo "  $0 -i key.pem -u ec2-user -h myserver.com --fix-only"
    exit 1
}

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Parse arguments
DEPLOY_FIX=true
DEPLOY_OUCH=true

while [[ $# -gt 0 ]]; do
    case $1 in
        -i|--identity)
            PEM_FILE="$2"
            shift 2
            ;;
        -u|--user)
            REMOTE_USER="$2"
            shift 2
            ;;
        -h|--host)
            REMOTE_HOST="$2"
            shift 2
            ;;
        -d|--deploy-dir)
            DEPLOY_DIR="$2"
            shift 2
            ;;
        -s|--ssh-port)
            SSH_PORT="$2"
            shift 2
            ;;
        --fix-only)
            DEPLOY_OUCH=false
            shift
            ;;
        --ouch-only)
            DEPLOY_FIX=false
            shift
            ;;
        --help)
            usage
            ;;
        *)
            log_error "Unknown option: $1"
            usage
            ;;
    esac
done

# Validate required arguments
if [ -z "$PEM_FILE" ] || [ -z "$REMOTE_USER" ] || [ -z "$REMOTE_HOST" ]; then
    log_error "Missing required arguments"
    usage
fi

# Check PEM file exists
if [ ! -f "$PEM_FILE" ]; then
    log_error "PEM file not found: $PEM_FILE"
    exit 1
fi

# Find distribution files
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ "$DEPLOY_FIX" = true ]; then
    FIX_DIST=$(ls -t "$SCRIPT_DIR/apps/fix-samples/target/"fix-samples-*-dist.tar.gz 2>/dev/null | head -1)
    if [ -z "$FIX_DIST" ] || [ ! -f "$FIX_DIST" ]; then
        log_error "FIX distribution not found. Run 'mvn package' in apps/fix-samples first."
        exit 1
    fi
    log_info "FIX distribution: $FIX_DIST"
fi

if [ "$DEPLOY_OUCH" = true ]; then
    OUCH_DIST=$(ls -t "$SCRIPT_DIR/apps/ouch-samples/target/"ouch-samples-*-dist.tar.gz 2>/dev/null | head -1)
    if [ -z "$OUCH_DIST" ] || [ ! -f "$OUCH_DIST" ]; then
        log_error "OUCH distribution not found. Run 'mvn package' in apps/ouch-samples first."
        exit 1
    fi
    log_info "OUCH distribution: $OUCH_DIST"
fi

log_info "Deploying to $REMOTE_USER@$REMOTE_HOST:$DEPLOY_DIR"

# SSH options
SSH_OPTS="-i $PEM_FILE -p $SSH_PORT -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10"
SCP_OPTS="-i $PEM_FILE -P $SSH_PORT -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10"

# Test SSH connection
log_info "Testing SSH connection..."
if ! ssh $SSH_OPTS "$REMOTE_USER@$REMOTE_HOST" "echo 'Connection successful'" 2>/dev/null; then
    log_error "Failed to connect to $REMOTE_USER@$REMOTE_HOST"
    exit 1
fi

# Create deployment directories
log_info "Creating deployment directories..."
ssh $SSH_OPTS "$REMOTE_USER@$REMOTE_HOST" "sudo mkdir -p $DEPLOY_DIR && sudo chown $REMOTE_USER:$REMOTE_USER $DEPLOY_DIR"

# Copy and deploy FIX acceptor
if [ "$DEPLOY_FIX" = true ]; then
    log_info "Deploying FIX Acceptor..."
    FIX_BASENAME=$(basename "$FIX_DIST")
    scp $SCP_OPTS "$FIX_DIST" "$REMOTE_USER@$REMOTE_HOST:/tmp/$FIX_BASENAME"

    ssh $SSH_OPTS "$REMOTE_USER@$REMOTE_HOST" bash -s << REMOTE_FIX
set -e
DEPLOY_DIR="$DEPLOY_DIR"
FIX_BASENAME="$FIX_BASENAME"
FIX_ADMIN_PORT="$FIX_ADMIN_PORT"

# Stop existing FIX acceptor if running
FIX_PID=\$(pgrep -f "SampleAcceptor" 2>/dev/null || true)
if [ -n "\$FIX_PID" ]; then
    echo "Stopping existing FIX acceptor (PID: \$FIX_PID)..."
    kill \$FIX_PID 2>/dev/null || true
    sleep 2
    kill -9 \$FIX_PID 2>/dev/null || true
fi

# Extract FIX distribution
echo "Extracting FIX distribution..."
cd /tmp
tar -xzf "\$FIX_BASENAME"
FIX_DIR=\$(tar -tzf "\$FIX_BASENAME" | head -1 | cut -d'/' -f1)

# Install FIX acceptor
rm -rf "\$DEPLOY_DIR/fix-acceptor"
mv "/tmp/\$FIX_DIR" "\$DEPLOY_DIR/fix-acceptor"
chmod +x "\$DEPLOY_DIR/fix-acceptor/bin/"*.sh
rm -f "/tmp/\$FIX_BASENAME"

# Update admin port in config if needed
if grep -q "admin {" "\$DEPLOY_DIR/fix-acceptor/conf/acceptor.conf"; then
    sed -i "s/port = 8081/port = \$FIX_ADMIN_PORT/" "\$DEPLOY_DIR/fix-acceptor/conf/acceptor.conf" 2>/dev/null || true
fi

echo "FIX Acceptor installed to \$DEPLOY_DIR/fix-acceptor"
REMOTE_FIX
fi

# Copy and deploy OUCH acceptor
if [ "$DEPLOY_OUCH" = true ]; then
    log_info "Deploying OUCH Acceptor..."
    OUCH_BASENAME=$(basename "$OUCH_DIST")
    scp $SCP_OPTS "$OUCH_DIST" "$REMOTE_USER@$REMOTE_HOST:/tmp/$OUCH_BASENAME"

    ssh $SSH_OPTS "$REMOTE_USER@$REMOTE_HOST" bash -s << REMOTE_OUCH
set -e
DEPLOY_DIR="$DEPLOY_DIR"
OUCH_BASENAME="$OUCH_BASENAME"
OUCH_ADMIN_PORT="$OUCH_ADMIN_PORT"

# Stop existing OUCH acceptor if running
OUCH_PID=\$(pgrep -f "SampleOuchAcceptor" 2>/dev/null || true)
if [ -n "\$OUCH_PID" ]; then
    echo "Stopping existing OUCH acceptor (PID: \$OUCH_PID)..."
    kill \$OUCH_PID 2>/dev/null || true
    sleep 2
    kill -9 \$OUCH_PID 2>/dev/null || true
fi

# Extract OUCH distribution
echo "Extracting OUCH distribution..."
cd /tmp
tar -xzf "\$OUCH_BASENAME"
OUCH_DIR=\$(tar -tzf "\$OUCH_BASENAME" | head -1 | cut -d'/' -f1)

# Install OUCH acceptor
rm -rf "\$DEPLOY_DIR/ouch-acceptor"
mv "/tmp/\$OUCH_DIR" "\$DEPLOY_DIR/ouch-acceptor"
chmod +x "\$DEPLOY_DIR/ouch-acceptor/bin/"*.sh
rm -f "/tmp/\$OUCH_BASENAME"

# Update admin port in config to avoid conflict with FIX
sed -i "s/port = 8081/port = \$OUCH_ADMIN_PORT/" "\$DEPLOY_DIR/ouch-acceptor/conf/ouch-acceptor.conf" 2>/dev/null || true

echo "OUCH Acceptor installed to \$DEPLOY_DIR/ouch-acceptor"
REMOTE_OUCH
fi

# Create management script on remote server
log_info "Creating management script..."
ssh $SSH_OPTS "$REMOTE_USER@$REMOTE_HOST" bash -s << 'REMOTE_SCRIPT'
DEPLOY_DIR="$DEPLOY_DIR"
cat > "$DEPLOY_DIR/samples.sh" << 'MGMT_SCRIPT'
#!/bin/bash
#
# Sample Applications Management Script
# Usage: samples.sh {start|stop|status|restart} [fix|ouch|all]
#

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FIX_HOME="$SCRIPT_DIR/fix-acceptor"
OUCH_HOME="$SCRIPT_DIR/ouch-acceptor"

# PID files
FIX_PID_FILE="$FIX_HOME/fix-acceptor.pid"
OUCH_PID_FILE="$OUCH_HOME/ouch-acceptor.pid"

start_fix() {
    if [ ! -d "$FIX_HOME" ]; then
        echo "FIX acceptor not installed"
        return 1
    fi

    if [ -f "$FIX_PID_FILE" ] && ps -p $(cat "$FIX_PID_FILE") > /dev/null 2>&1; then
        echo "FIX acceptor already running (PID: $(cat $FIX_PID_FILE))"
        return 0
    fi

    echo "Starting FIX acceptor..."
    cd "$FIX_HOME"
    mkdir -p logs
    nohup bin/fix-acceptor.sh -c conf/acceptor.conf > logs/acceptor.log 2>&1 &
    echo $! > "$FIX_PID_FILE"
    sleep 2

    if ps -p $(cat "$FIX_PID_FILE") > /dev/null 2>&1; then
        echo "FIX acceptor started (PID: $(cat $FIX_PID_FILE))"
        echo "  FIX port: 9876, Admin port: 8081"
    else
        echo "FIX acceptor failed to start"
        rm -f "$FIX_PID_FILE"
        return 1
    fi
}

start_ouch() {
    if [ ! -d "$OUCH_HOME" ]; then
        echo "OUCH acceptor not installed"
        return 1
    fi

    if [ -f "$OUCH_PID_FILE" ] && ps -p $(cat "$OUCH_PID_FILE") > /dev/null 2>&1; then
        echo "OUCH acceptor already running (PID: $(cat $OUCH_PID_FILE))"
        return 0
    fi

    echo "Starting OUCH acceptor..."
    cd "$OUCH_HOME"
    mkdir -p logs
    nohup bin/ouch-acceptor.sh -c conf/ouch-acceptor.conf > logs/acceptor.log 2>&1 &
    echo $! > "$OUCH_PID_FILE"
    sleep 2

    if ps -p $(cat "$OUCH_PID_FILE") > /dev/null 2>&1; then
        echo "OUCH acceptor started (PID: $(cat $OUCH_PID_FILE))"
        echo "  OUCH port: 9200, Admin port: 8082"
    else
        echo "OUCH acceptor failed to start"
        rm -f "$OUCH_PID_FILE"
        return 1
    fi
}

stop_fix() {
    if [ -f "$FIX_PID_FILE" ]; then
        PID=$(cat "$FIX_PID_FILE")
        if ps -p $PID > /dev/null 2>&1; then
            echo "Stopping FIX acceptor (PID: $PID)..."
            kill $PID 2>/dev/null
            sleep 2
            if ps -p $PID > /dev/null 2>&1; then
                kill -9 $PID 2>/dev/null
            fi
        fi
        rm -f "$FIX_PID_FILE"
        echo "FIX acceptor stopped"
    else
        # Try to find by process name
        PID=$(pgrep -f "SampleAcceptor" 2>/dev/null || true)
        if [ -n "$PID" ]; then
            echo "Stopping FIX acceptor (PID: $PID)..."
            kill $PID 2>/dev/null
            sleep 2
            kill -9 $PID 2>/dev/null || true
            echo "FIX acceptor stopped"
        else
            echo "FIX acceptor not running"
        fi
    fi
}

stop_ouch() {
    if [ -f "$OUCH_PID_FILE" ]; then
        PID=$(cat "$OUCH_PID_FILE")
        if ps -p $PID > /dev/null 2>&1; then
            echo "Stopping OUCH acceptor (PID: $PID)..."
            kill $PID 2>/dev/null
            sleep 2
            if ps -p $PID > /dev/null 2>&1; then
                kill -9 $PID 2>/dev/null
            fi
        fi
        rm -f "$OUCH_PID_FILE"
        echo "OUCH acceptor stopped"
    else
        # Try to find by process name
        PID=$(pgrep -f "SampleOuchAcceptor" 2>/dev/null || true)
        if [ -n "$PID" ]; then
            echo "Stopping OUCH acceptor (PID: $PID)..."
            kill $PID 2>/dev/null
            sleep 2
            kill -9 $PID 2>/dev/null || true
            echo "OUCH acceptor stopped"
        else
            echo "OUCH acceptor not running"
        fi
    fi
}

status_fix() {
    if [ -f "$FIX_PID_FILE" ] && ps -p $(cat "$FIX_PID_FILE") > /dev/null 2>&1; then
        echo "FIX acceptor: RUNNING (PID: $(cat $FIX_PID_FILE))"
        echo "  FIX port: 9876, Admin port: 8081"
    else
        PID=$(pgrep -f "SampleAcceptor" 2>/dev/null || true)
        if [ -n "$PID" ]; then
            echo "FIX acceptor: RUNNING (PID: $PID) - no PID file"
        else
            echo "FIX acceptor: STOPPED"
        fi
    fi
}

status_ouch() {
    if [ -f "$OUCH_PID_FILE" ] && ps -p $(cat "$OUCH_PID_FILE") > /dev/null 2>&1; then
        echo "OUCH acceptor: RUNNING (PID: $(cat $OUCH_PID_FILE))"
        echo "  OUCH port: 9200, Admin port: 8082"
    else
        PID=$(pgrep -f "SampleOuchAcceptor" 2>/dev/null || true)
        if [ -n "$PID" ]; then
            echo "OUCH acceptor: RUNNING (PID: $PID) - no PID file"
        else
            echo "OUCH acceptor: STOPPED"
        fi
    fi
}

COMMAND=$1
TARGET=${2:-all}

case "$COMMAND" in
    start)
        case "$TARGET" in
            fix) start_fix ;;
            ouch) start_ouch ;;
            all) start_fix; start_ouch ;;
            *) echo "Unknown target: $TARGET"; exit 1 ;;
        esac
        ;;
    stop)
        case "$TARGET" in
            fix) stop_fix ;;
            ouch) stop_ouch ;;
            all) stop_fix; stop_ouch ;;
            *) echo "Unknown target: $TARGET"; exit 1 ;;
        esac
        ;;
    status)
        case "$TARGET" in
            fix) status_fix ;;
            ouch) status_ouch ;;
            all) status_fix; status_ouch ;;
            *) echo "Unknown target: $TARGET"; exit 1 ;;
        esac
        ;;
    restart)
        case "$TARGET" in
            fix) stop_fix; sleep 1; start_fix ;;
            ouch) stop_ouch; sleep 1; start_ouch ;;
            all) stop_fix; stop_ouch; sleep 1; start_fix; start_ouch ;;
            *) echo "Unknown target: $TARGET"; exit 1 ;;
        esac
        ;;
    *)
        echo "Usage: $0 {start|stop|status|restart} [fix|ouch|all]"
        echo ""
        echo "Commands:"
        echo "  start   - Start acceptor(s)"
        echo "  stop    - Stop acceptor(s)"
        echo "  status  - Check acceptor status"
        echo "  restart - Restart acceptor(s)"
        echo ""
        echo "Targets:"
        echo "  fix     - FIX acceptor only"
        echo "  ouch    - OUCH acceptor only"
        echo "  all     - Both acceptors (default)"
        exit 1
        ;;
esac
MGMT_SCRIPT
chmod +x "$DEPLOY_DIR/samples.sh"
REMOTE_SCRIPT

# Fix the DEPLOY_DIR in the management script
ssh $SSH_OPTS "$REMOTE_USER@$REMOTE_HOST" "sed -i '1a DEPLOY_DIR=\"$DEPLOY_DIR\"' $DEPLOY_DIR/samples.sh"

# Start the acceptors
log_info "Starting acceptors..."
ssh $SSH_OPTS "$REMOTE_USER@$REMOTE_HOST" "$DEPLOY_DIR/samples.sh restart all"

log_info "Deployment completed successfully!"
echo ""
echo "Sample applications deployed to: $DEPLOY_DIR"
echo ""
echo "Port assignments:"
echo "  FIX Acceptor:  9876 (FIX protocol), 8081 (Admin API)"
echo "  OUCH Acceptor: 9200 (OUCH protocol), 8082 (Admin API)"
echo ""
echo "Management commands:"
echo "  ssh $REMOTE_USER@$REMOTE_HOST '$DEPLOY_DIR/samples.sh status'"
echo "  ssh $REMOTE_USER@$REMOTE_HOST '$DEPLOY_DIR/samples.sh restart all'"
echo "  ssh $REMOTE_USER@$REMOTE_HOST '$DEPLOY_DIR/samples.sh stop fix'"
