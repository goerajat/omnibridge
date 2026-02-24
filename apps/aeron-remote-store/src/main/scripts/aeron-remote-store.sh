#!/bin/bash
# =============================================================================
# Aeron Remote Persistence Store Management Script
# =============================================================================
# Usage: aeron-remote-store.sh {start|stop|status|restart} [options]
#
# Options:
#   -c, --config   Path to configuration file
#   -d, --debug    Enable debug logging
#   -h, --help     Show this help message
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BASE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LIB_DIR="$BASE_DIR/lib"
CONF_DIR="$BASE_DIR/conf"
LOG_DIR="$BASE_DIR/logs"
DATA_DIR="$BASE_DIR/data"
PID_FILE="$BASE_DIR/aeron-remote-store.pid"

# Default values
CONFIG_FILE="$CONF_DIR/aeron-remote-store.conf"
DEBUG_MODE=false

# Java options
JAVA_OPTS="-Xms1g -Xmx2g"
JAVA_OPTS="$JAVA_OPTS -XX:+UseZGC -XX:+AlwaysPreTouch"
JAVA_OPTS="$JAVA_OPTS -Djava.net.preferIPv4Stack=true"
JAVA_OPTS="$JAVA_OPTS -Dlogback.configurationFile=$CONF_DIR/logback.xml"

# Chronicle Queue / Aeron --add-opens and --add-exports for Java 17+
JAVA_OPTS="$JAVA_OPTS --add-opens java.base/java.lang=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-opens java.base/java.lang.reflect=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-opens java.base/java.io=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-opens java.base/java.nio=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-opens java.base/sun.nio.ch=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-opens java.base/jdk.internal.misc=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-exports java.base/jdk.internal.misc=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-exports java.base/jdk.internal.ref=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-exports java.base/sun.nio.ch=ALL-UNNAMED"

# Parse arguments
parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -c|--config)
                CONFIG_FILE="$2"
                shift 2
                ;;
            -d|--debug)
                DEBUG_MODE=true
                shift
                ;;
            -h|--help)
                show_help
                exit 0
                ;;
            start|stop|status|restart)
                COMMAND="$1"
                shift
                ;;
            *)
                echo "Unknown option: $1"
                show_help
                exit 1
                ;;
        esac
    done
}

show_help() {
    echo "Aeron Remote Persistence Store Management Script"
    echo ""
    echo "Usage: $0 {start|stop|status|restart} [options]"
    echo ""
    echo "Commands:"
    echo "  start     Start the remote store"
    echo "  stop      Stop the remote store"
    echo "  status    Check if the remote store is running"
    echo "  restart   Restart the remote store"
    echo ""
    echo "Options:"
    echo "  -c, --config   Path to configuration file (default: conf/aeron-remote-store.conf)"
    echo "  -d, --debug    Enable debug logging"
    echo "  -h, --help     Show this help message"
}

get_pid() {
    if [ -f "$PID_FILE" ]; then
        cat "$PID_FILE"
    fi
}

is_running() {
    local pid=$(get_pid)
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        return 0
    fi
    return 1
}

start_store() {
    if is_running; then
        echo "Aeron Remote Store is already running (PID: $(get_pid))"
        return 1
    fi

    echo "Starting Aeron Remote Store..."

    # Create directories if needed
    mkdir -p "$LOG_DIR"
    mkdir -p "$DATA_DIR"

    # Build classpath
    CLASSPATH="$CONF_DIR:$LIB_DIR/aeron-remote-store.jar"

    # Enable debug if requested
    if [ "$DEBUG_MODE" = true ]; then
        JAVA_OPTS="$JAVA_OPTS -Dlogging.level.root=DEBUG"
    fi

    # Start the application
    nohup java $JAVA_OPTS -cp "$CLASSPATH" \
        com.omnibridge.persistence.aeron.AeronRemoteStoreMain \
        -c "$CONFIG_FILE" \
        > "$LOG_DIR/console.log" 2>&1 &

    local pid=$!
    echo $pid > "$PID_FILE"

    # Wait a moment and check if it started
    sleep 2
    if is_running; then
        echo "Aeron Remote Store started (PID: $pid)"
        echo "Config: $CONFIG_FILE"
        echo "Logs: $LOG_DIR"
        return 0
    else
        echo "Failed to start Aeron Remote Store"
        rm -f "$PID_FILE"
        return 1
    fi
}

stop_store() {
    if ! is_running; then
        echo "Aeron Remote Store is not running"
        rm -f "$PID_FILE"
        return 0
    fi

    local pid=$(get_pid)
    echo "Stopping Aeron Remote Store (PID: $pid)..."

    kill "$pid" 2>/dev/null

    # Wait for graceful shutdown
    local count=0
    while is_running && [ $count -lt 30 ]; do
        sleep 1
        count=$((count + 1))
    done

    if is_running; then
        echo "Forcing shutdown..."
        kill -9 "$pid" 2>/dev/null
        sleep 1
    fi

    rm -f "$PID_FILE"
    echo "Aeron Remote Store stopped"
}

show_status() {
    if is_running; then
        echo "Aeron Remote Store is running (PID: $(get_pid))"
    else
        echo "Aeron Remote Store is not running"
    fi
}

restart_store() {
    stop_store
    sleep 2
    start_store
}

# Main
parse_args "$@"

case "$COMMAND" in
    start)
        start_store
        ;;
    stop)
        stop_store
        ;;
    status)
        show_status
        ;;
    restart)
        restart_store
        ;;
    *)
        show_help
        exit 1
        ;;
esac
