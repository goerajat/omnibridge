#!/bin/bash
#
# Remote deployment helper script for OmniView
# This script is executed on the remote server during deployment
#
# Usage: deploy-omniview-remote.sh <deploy-dir> <dist-file> <port> <version>
#

set -e

DEPLOY_DIR="$1"
REMOTE_TMP="$2"
REMOTE_PORT="$3"
VERSION="$4"

echo "Extracting distribution..."
cd /tmp
tar -xzf "$REMOTE_TMP"

# Stop existing instance if running
if [ -f "$DEPLOY_DIR/bin/omniview.sh" ]; then
    echo "Stopping existing OmniView instance..."
    $DEPLOY_DIR/bin/omniview.sh stop || true
    sleep 2
fi

# Backup existing installation
if [ -d "$DEPLOY_DIR/lib" ]; then
    echo "Backing up existing installation..."
    BACKUP_DIR="$DEPLOY_DIR/backup/$(date +%Y%m%d_%H%M%S)"
    mkdir -p "$BACKUP_DIR"
    cp -r "$DEPLOY_DIR/lib" "$BACKUP_DIR/" 2>/dev/null || true
    cp -r "$DEPLOY_DIR/bin" "$BACKUP_DIR/" 2>/dev/null || true
fi

# Deploy new version
echo "Installing new version..."
rm -rf "$DEPLOY_DIR/lib" "$DEPLOY_DIR/bin"
cp -r /tmp/omniview-$VERSION/* "$DEPLOY_DIR/"

# Ensure scripts are executable
chmod +x "$DEPLOY_DIR/bin/"*.sh

# Clean up
rm -rf "/tmp/omniview-$VERSION" "$REMOTE_TMP"

# Start OmniView
echo "Starting OmniView..."
$DEPLOY_DIR/bin/omniview.sh start $REMOTE_PORT

echo ""
echo "Deployment complete!"
