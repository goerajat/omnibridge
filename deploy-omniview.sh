#!/bin/bash
#
# Deploy OmniView to a remote Linux server
#
# Usage: ./deploy-omniview.sh -i <pem-file> -u <username> -h <hostname> [-p <port>] [-d <deploy-dir>]
#
# This script:
#   1. Copies the distribution package to the remote server
#   2. Extracts the package
#   3. Stops any running OmniView instance
#   4. Starts the new version
#

set -e

# Default values
REMOTE_PORT=3000
DEPLOY_DIR="/opt/omniview"
SSH_PORT=22

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Print usage
usage() {
    echo "Usage: $0 -i <pem-file> -u <username> -h <hostname> [options]"
    echo ""
    echo "Required arguments:"
    echo "  -i, --identity <pem-file>   Path to the PEM file for SSH authentication"
    echo "  -u, --user <username>       SSH username"
    echo "  -h, --host <hostname>       Remote hostname or IP address"
    echo ""
    echo "Optional arguments:"
    echo "  -p, --port <port>           OmniView server port (default: 3000)"
    echo "  -d, --deploy-dir <dir>      Deployment directory (default: /opt/omniview)"
    echo "  -s, --ssh-port <port>       SSH port (default: 22)"
    echo "  --dist <file>               Distribution file to deploy (default: auto-detect)"
    echo "  --help                      Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 -i ~/.ssh/mykey.pem -u ubuntu -h 192.168.1.100"
    echo "  $0 -i key.pem -u ec2-user -h myserver.com -p 8080 -d /home/ec2-user/omniview"
    exit 1
}

# Print colored message
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
        -p|--port)
            REMOTE_PORT="$2"
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
        --dist)
            DIST_FILE="$2"
            shift 2
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

# Find distribution file
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -z "$DIST_FILE" ]; then
    DIST_FILE=$(ls -t "$SCRIPT_DIR/omniview/target/"omniview-*-dist.tar.gz 2>/dev/null | head -1)
    if [ -z "$DIST_FILE" ]; then
        log_error "Distribution file not found. Run 'mvn package' in omniview/ first."
        exit 1
    fi
fi

if [ ! -f "$DIST_FILE" ]; then
    log_error "Distribution file not found: $DIST_FILE"
    exit 1
fi

# Extract version from distribution file
DIST_BASENAME=$(basename "$DIST_FILE")
VERSION=$(echo "$DIST_BASENAME" | sed -E 's/omniview-(.+)-dist\.tar\.gz/\1/')

log_info "Deploying OmniView $VERSION to $REMOTE_USER@$REMOTE_HOST"
log_info "Distribution: $DIST_FILE"
log_info "Deploy directory: $DEPLOY_DIR"
log_info "Server port: $REMOTE_PORT"

# SSH options
SSH_OPTS="-i $PEM_FILE -p $SSH_PORT -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10"
SCP_OPTS="-i $PEM_FILE -P $SSH_PORT -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10"

# Test SSH connection
log_info "Testing SSH connection..."
if ! ssh $SSH_OPTS "$REMOTE_USER@$REMOTE_HOST" "echo 'Connection successful'" 2>/dev/null; then
    log_error "Failed to connect to $REMOTE_USER@$REMOTE_HOST"
    exit 1
fi

# Create deployment directory on remote
log_info "Creating deployment directory..."
ssh $SSH_OPTS "$REMOTE_USER@$REMOTE_HOST" "sudo mkdir -p $DEPLOY_DIR && sudo chown $REMOTE_USER:$REMOTE_USER $DEPLOY_DIR"

# Copy distribution file
log_info "Copying distribution package..."
REMOTE_TMP="/tmp/$DIST_BASENAME"
scp $SCP_OPTS "$DIST_FILE" "$REMOTE_USER@$REMOTE_HOST:$REMOTE_TMP"

# Deploy on remote server
log_info "Deploying on remote server..."
ssh $SSH_OPTS "$REMOTE_USER@$REMOTE_HOST" bash -s << REMOTE_SCRIPT
set -e

DEPLOY_DIR="$DEPLOY_DIR"
REMOTE_TMP="$REMOTE_TMP"
REMOTE_PORT="$REMOTE_PORT"
VERSION="$VERSION"

echo "Extracting distribution..."
cd /tmp
tar -xzf "\$REMOTE_TMP"

# Stop existing instance if running
if [ -f "\$DEPLOY_DIR/bin/omniview.sh" ]; then
    echo "Stopping existing OmniView instance..."
    \$DEPLOY_DIR/bin/omniview.sh stop || true
    sleep 2
fi

# Backup existing installation
if [ -d "\$DEPLOY_DIR/lib" ]; then
    echo "Backing up existing installation..."
    BACKUP_DIR="\$DEPLOY_DIR/backup/\$(date +%Y%m%d_%H%M%S)"
    mkdir -p "\$BACKUP_DIR"
    cp -r "\$DEPLOY_DIR/lib" "\$BACKUP_DIR/" 2>/dev/null || true
    cp -r "\$DEPLOY_DIR/bin" "\$BACKUP_DIR/" 2>/dev/null || true
fi

# Deploy new version
echo "Installing new version..."
rm -rf "\$DEPLOY_DIR/lib" "\$DEPLOY_DIR/bin"
cp -r /tmp/omniview-\$VERSION/* "\$DEPLOY_DIR/"

# Ensure scripts are executable
chmod +x "\$DEPLOY_DIR/bin/"*.sh

# Clean up
rm -rf "/tmp/omniview-\$VERSION" "\$REMOTE_TMP"

# Start OmniView
echo "Starting OmniView..."
\$DEPLOY_DIR/bin/omniview.sh start \$REMOTE_PORT

echo ""
echo "Deployment complete!"
REMOTE_SCRIPT

log_info "Deployment completed successfully!"
log_info "OmniView is running at http://$REMOTE_HOST:$REMOTE_PORT"
