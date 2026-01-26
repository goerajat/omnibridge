#!/bin/bash
# =============================================================================
# Exchange Simulator Deployment Script
# =============================================================================
# Deploys the exchange simulator to a remote Linux server
#
# Usage: ./deploy-exchange-simulator.sh -i <pem-file> -u <username> -h <hostname> [options]
#
# Options:
#   -i, --identity    Path to PEM file for SSH authentication (required)
#   -u, --user        SSH username (required)
#   -h, --host        Remote hostname or IP (required)
#   -p, --port        Simulator admin port (default: 8080)
#   -d, --deploy-dir  Deployment directory (default: /opt/exchange-simulator)
#   -s, --ssh-port    SSH port (default: 22)
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Default values
ADMIN_PORT=8080
DEPLOY_DIR="/opt/exchange-simulator"
SSH_PORT=22

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        -i|--identity)
            PEM_FILE="$2"
            shift 2
            ;;
        -u|--user)
            SSH_USER="$2"
            shift 2
            ;;
        -h|--host)
            SSH_HOST="$2"
            shift 2
            ;;
        -p|--port)
            ADMIN_PORT="$2"
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
        --help)
            echo "Usage: $0 -i <pem-file> -u <username> -h <hostname> [options]"
            echo ""
            echo "Options:"
            echo "  -i, --identity    Path to PEM file for SSH authentication (required)"
            echo "  -u, --user        SSH username (required)"
            echo "  -h, --host        Remote hostname or IP (required)"
            echo "  -p, --port        Simulator admin port (default: 8080)"
            echo "  -d, --deploy-dir  Deployment directory (default: /opt/exchange-simulator)"
            echo "  -s, --ssh-port    SSH port (default: 22)"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Validate required arguments
if [ -z "$PEM_FILE" ] || [ -z "$SSH_USER" ] || [ -z "$SSH_HOST" ]; then
    echo "Error: Missing required arguments"
    echo "Usage: $0 -i <pem-file> -u <username> -h <hostname>"
    exit 1
fi

if [ ! -f "$PEM_FILE" ]; then
    echo "Error: PEM file not found: $PEM_FILE"
    exit 1
fi

# Find distribution package
DIST_DIR="$SCRIPT_DIR/apps/exchange-simulator/target"
DIST_PACKAGE=$(ls "$DIST_DIR"/exchange-simulator-*-dist.tar.gz 2>/dev/null | head -1)

if [ -z "$DIST_PACKAGE" ]; then
    echo "Error: Distribution package not found in $DIST_DIR"
    echo "Please build the project first: mvn package -DskipTests"
    exit 1
fi

echo "========================================================"
echo "Exchange Simulator Deployment"
echo "========================================================"
echo "Target: $SSH_USER@$SSH_HOST:$SSH_PORT"
echo "Deploy Dir: $DEPLOY_DIR"
echo "Admin Port: $ADMIN_PORT"
echo "Package: $(basename "$DIST_PACKAGE")"
echo "========================================================"

SSH_OPTS="-i $PEM_FILE -o StrictHostKeyChecking=no -p $SSH_PORT"
SCP_OPTS="-i $PEM_FILE -o StrictHostKeyChecking=no -P $SSH_PORT"

# Upload distribution package
echo "Uploading distribution package..."
scp $SCP_OPTS "$DIST_PACKAGE" "$SSH_USER@$SSH_HOST:/tmp/"

# Deploy on remote server
echo "Deploying on remote server..."
ssh $SSH_OPTS "$SSH_USER@$SSH_HOST" << REMOTE_SCRIPT
set -e

# Stop existing instance if running
if [ -f "$DEPLOY_DIR/bin/exchange-simulator.sh" ]; then
    echo "Stopping existing instance..."
    sudo "$DEPLOY_DIR/bin/exchange-simulator.sh" stop || true
fi

# Create deployment directory
sudo mkdir -p "$DEPLOY_DIR"
sudo chown -R $SSH_USER:$SSH_USER "$DEPLOY_DIR"

# Extract distribution
echo "Extracting distribution..."
cd /tmp
tar xzf exchange-simulator-*-dist.tar.gz
rm -rf "$DEPLOY_DIR"/*
cp -r exchange-simulator-*/* "$DEPLOY_DIR/"
rm -rf exchange-simulator-*

# Make scripts executable
chmod +x "$DEPLOY_DIR/bin/"*.sh

# Start the simulator
echo "Starting Exchange Simulator..."
"$DEPLOY_DIR/bin/exchange-simulator.sh" start -p $ADMIN_PORT

echo ""
echo "========================================================"
echo "Deployment Complete!"
echo "========================================================"
echo "Admin API: http://$SSH_HOST:$ADMIN_PORT"
echo ""
echo "Port Assignments:"
echo "  FIX:     9876-9880"
echo "  OUCH:    9200-9201"
echo "  iLink3:  9300"
echo "  Optiq:   9400"
echo "  Pillar:  9500"
echo "  Admin:   $ADMIN_PORT"
echo ""
echo "Management Commands:"
echo "  $DEPLOY_DIR/bin/exchange-simulator.sh status"
echo "  $DEPLOY_DIR/bin/exchange-simulator.sh stop"
echo "  $DEPLOY_DIR/bin/exchange-simulator.sh restart"
echo "========================================================"
REMOTE_SCRIPT

echo ""
echo "Deployment completed successfully!"
