#!/bin/bash
#
# OmniView - Protocol Engine Monitor
# Usage: omniview.sh {start|stop|status|restart} [port]
#

# Resolve script directory
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OMNIVIEW_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"

# Configuration
APP_NAME="omniview"
JAR_FILE="$OMNIVIEW_HOME/lib/omniview.jar"
PID_FILE="$OMNIVIEW_HOME/omniview.pid"
LOG_FILE="$OMNIVIEW_HOME/logs/omniview.log"
DEFAULT_PORT=3000

# Java options
JAVA_OPTS="${JAVA_OPTS:--Xms128m -Xmx512m}"

# Ensure logs directory exists
mkdir -p "$OMNIVIEW_HOME/logs"

# Get port from argument or use default
get_port() {
    if [ -n "$2" ]; then
        echo "$2"
    else
        echo "$DEFAULT_PORT"
    fi
}

# Check if OmniView is running
is_running() {
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if ps -p "$PID" > /dev/null 2>&1; then
            return 0
        fi
    fi
    return 1
}

# Get the running PID
get_pid() {
    if [ -f "$PID_FILE" ]; then
        cat "$PID_FILE"
    fi
}

# Start OmniView
start() {
    local PORT=$(get_port "$@")

    if is_running; then
        echo "OmniView is already running (PID: $(get_pid))"
        return 1
    fi

    echo "Starting OmniView on port $PORT..."

    # Check if JAR exists
    if [ ! -f "$JAR_FILE" ]; then
        echo "ERROR: JAR file not found: $JAR_FILE"
        return 1
    fi

    # Start the server
    nohup java $JAVA_OPTS -Dport="$PORT" -jar "$JAR_FILE" > "$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"

    # Wait and verify startup
    sleep 2

    if is_running; then
        echo "OmniView started successfully (PID: $(get_pid))"
        echo "Access the application at: http://localhost:$PORT"
        echo "Logs: $LOG_FILE"
        return 0
    else
        echo "ERROR: OmniView failed to start. Check logs at: $LOG_FILE"
        rm -f "$PID_FILE"
        tail -20 "$LOG_FILE"
        return 1
    fi
}

# Stop OmniView
stop() {
    if ! is_running; then
        echo "OmniView is not running"
        rm -f "$PID_FILE"
        return 0
    fi

    local PID=$(get_pid)
    echo "Stopping OmniView (PID: $PID)..."

    # Send SIGTERM for graceful shutdown
    kill "$PID" 2>/dev/null

    # Wait for graceful shutdown (up to 10 seconds)
    local COUNT=0
    while [ $COUNT -lt 20 ]; do
        if ! is_running; then
            echo "OmniView stopped successfully"
            rm -f "$PID_FILE"
            return 0
        fi
        sleep 0.5
        COUNT=$((COUNT + 1))
    done

    # Force kill if still running
    echo "Process not responding, forcing termination..."
    kill -9 "$PID" 2>/dev/null
    rm -f "$PID_FILE"
    echo "OmniView stopped"
    return 0
}

# Check OmniView status
status() {
    if is_running; then
        local PID=$(get_pid)
        echo "OmniView is running (PID: $PID)"

        # Try to get port from process
        local PORT_INFO=$(ps -p "$PID" -o args= 2>/dev/null | grep -oP '(?<=-Dport=)\d+' || echo "unknown")
        echo "Port: $PORT_INFO"
        echo "PID file: $PID_FILE"
        echo "Log file: $LOG_FILE"
        return 0
    else
        echo "OmniView is not running"
        if [ -f "$PID_FILE" ]; then
            echo "Stale PID file found, cleaning up..."
            rm -f "$PID_FILE"
        fi
        return 1
    fi
}

# Restart OmniView
restart() {
    local PORT=$(get_port "$@")
    stop
    sleep 1
    start "$PORT"
}

# Main
case "$1" in
    start)
        start "$@"
        ;;
    stop)
        stop
        ;;
    status)
        status
        ;;
    restart)
        restart "$@"
        ;;
    *)
        echo "Usage: $0 {start|stop|status|restart} [port]"
        echo ""
        echo "Commands:"
        echo "  start [port]   Start OmniView (default port: $DEFAULT_PORT)"
        echo "  stop           Stop OmniView"
        echo "  status         Check if OmniView is running"
        echo "  restart [port] Restart OmniView"
        echo ""
        echo "Environment variables:"
        echo "  JAVA_OPTS      JVM options (default: -Xms128m -Xmx512m)"
        exit 1
        ;;
esac

exit $?
