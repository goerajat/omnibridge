#!/bin/bash

APP_NAME="omniview"
PID_FILE="/tmp/${APP_NAME}.pid"

echo "Stopping OmniView..."

# Try to find PID from PID file
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    echo "Found PID file with PID: $PID"

    if ps -p "$PID" > /dev/null 2>&1; then
        echo "Terminating process $PID..."
        kill "$PID" 2>/dev/null

        # Wait for graceful shutdown
        for i in {1..10}; do
            if ! ps -p "$PID" > /dev/null 2>&1; then
                echo "OmniView stopped successfully."
                rm -f "$PID_FILE"
                exit 0
            fi
            sleep 0.5
        done

        # Force kill if still running
        echo "Process not responding, forcing termination..."
        kill -9 "$PID" 2>/dev/null
        rm -f "$PID_FILE"
        echo "OmniView stopped."
        exit 0
    else
        echo "Process $PID not found, cleaning up PID file."
        rm -f "$PID_FILE"
    fi
fi

# Fallback: Find by process name
PID=$(pgrep -f "omniview.*\.jar" 2>/dev/null | head -1)
if [ -n "$PID" ]; then
    echo "Found OmniView process with PID $PID"
    kill "$PID" 2>/dev/null

    # Wait for graceful shutdown
    for i in {1..10}; do
        if ! ps -p "$PID" > /dev/null 2>&1; then
            echo "OmniView stopped successfully."
            exit 0
        fi
        sleep 0.5
    done

    # Force kill
    kill -9 "$PID" 2>/dev/null
    echo "OmniView stopped."
    exit 0
fi

echo "OmniView is not running."
exit 1
