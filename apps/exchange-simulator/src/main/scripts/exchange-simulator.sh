#!/bin/bash
# =============================================================================
# Exchange Simulator Management Script
# =============================================================================
# Usage: exchange-simulator.sh {start|stop|status|restart} [options]
#
# Options:
#   -p, --port     Admin API port (default: 8080)
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
PID_FILE="$BASE_DIR/exchange-simulator.pid"

# Default values
ADMIN_PORT=8080
CONFIG_FILE=""
DEBUG_MODE=false

# Java options
JAVA_OPTS="-Xms512m -Xmx2g"
JAVA_OPTS="$JAVA_OPTS -XX:+UseG1GC"
JAVA_OPTS="$JAVA_OPTS -Dlogback.configurationFile=$CONF_DIR/logback.xml"

# Parse arguments
parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -p|--port)
                ADMIN_PORT="$2"
                shift 2
                ;;
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
    echo "Exchange Simulator Management Script"
    echo ""
    echo "Usage: $0 {start|stop|status|restart} [options]"
    echo ""
    echo "Commands:"
    echo "  start     Start the exchange simulator"
    echo "  stop      Stop the exchange simulator"
    echo "  status    Check if the simulator is running"
    echo "  restart   Restart the exchange simulator"
    echo ""
    echo "Options:"
    echo "  -p, --port     Admin API port (default: 8080)"
    echo "  -c, --config   Path to configuration file"
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

start_simulator() {
    if is_running; then
        echo "Exchange Simulator is already running (PID: $(get_pid))"
        return 1
    fi

    echo "Starting Exchange Simulator..."

    # Create directories if needed
    mkdir -p "$LOG_DIR"
    mkdir -p "$DATA_DIR"

    # Build classpath
    CLASSPATH="$CONF_DIR:$LIB_DIR/exchange-simulator.jar"

    # Add config file if specified
    APP_ARGS=""
    if [ -n "$CONFIG_FILE" ]; then
        APP_ARGS="$APP_ARGS -Dconfig.file=$CONFIG_FILE"
    fi

    # Enable debug if requested
    if [ "$DEBUG_MODE" = true ]; then
        JAVA_OPTS="$JAVA_OPTS -Dlogging.level.root=DEBUG"
    fi

    # Override admin port
    APP_ARGS="$APP_ARGS -Dadmin.port=$ADMIN_PORT"

    # Start the application
    nohup java $JAVA_OPTS $APP_ARGS -cp "$CLASSPATH" \
        com.omnibridge.simulator.ExchangeSimulator \
        > "$LOG_DIR/console.log" 2>&1 &

    local pid=$!
    echo $pid > "$PID_FILE"

    # Wait a moment and check if it started
    sleep 2
    if is_running; then
        echo "Exchange Simulator started (PID: $pid)"
        echo "Admin API: http://localhost:$ADMIN_PORT"
        echo "Logs: $LOG_DIR"
        return 0
    else
        echo "Failed to start Exchange Simulator"
        rm -f "$PID_FILE"
        return 1
    fi
}

stop_simulator() {
    if ! is_running; then
        echo "Exchange Simulator is not running"
        rm -f "$PID_FILE"
        return 0
    fi

    local pid=$(get_pid)
    echo "Stopping Exchange Simulator (PID: $pid)..."

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
    echo "Exchange Simulator stopped"
}

show_status() {
    if is_running; then
        echo "Exchange Simulator is running (PID: $(get_pid))"
        echo "Admin API: http://localhost:$ADMIN_PORT"
    else
        echo "Exchange Simulator is not running"
    fi
}

restart_simulator() {
    stop_simulator
    sleep 2
    start_simulator
}

# Main
parse_args "$@"

case "$COMMAND" in
    start)
        start_simulator
        ;;
    stop)
        stop_simulator
        ;;
    status)
        show_status
        ;;
    restart)
        restart_simulator
        ;;
    *)
        show_help
        exit 1
        ;;
esac
