#!/bin/bash

APP_NAME="omniview"
DEFAULT_PORT=3000
JAR_FILE="omniview/target/omniview-1.0.0-SNAPSHOT.jar"
PID_FILE="/tmp/${APP_NAME}.pid"
LOG_FILE="/tmp/${APP_NAME}.log"

# Parse command line arguments
PORT=${1:-$DEFAULT_PORT}

# Check if JAR exists
if [ ! -f "$JAR_FILE" ]; then
    echo "ERROR: JAR file not found: $JAR_FILE"
    echo "Please build OmniView first with: cd omniview && mvn package"
    exit 1
fi

# Check if already running
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if ps -p "$OLD_PID" > /dev/null 2>&1; then
        echo "OmniView is already running with PID $OLD_PID"
        exit 1
    else
        # Stale PID file, remove it
        rm -f "$PID_FILE"
    fi
fi

# Also check by process name
EXISTING_PID=$(pgrep -f "omniview.*\.jar" 2>/dev/null | head -1)
if [ -n "$EXISTING_PID" ]; then
    echo "OmniView is already running with PID $EXISTING_PID"
    exit 1
fi

echo "Starting OmniView on port $PORT..."

# Start the server in background
nohup java -Dport="$PORT" -jar "$JAR_FILE" > "$LOG_FILE" 2>&1 &
SERVER_PID=$!

# Wait a moment for startup
sleep 2

# Verify it started
if ps -p "$SERVER_PID" > /dev/null 2>&1; then
    echo "OmniView started successfully with PID $SERVER_PID"
    echo "Access the application at: http://localhost:$PORT"
    echo "Logs: $LOG_FILE"
else
    echo "ERROR: OmniView failed to start. Check logs at: $LOG_FILE"
    cat "$LOG_FILE"
    exit 1
fi
