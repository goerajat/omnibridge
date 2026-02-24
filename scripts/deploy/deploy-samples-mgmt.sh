#!/bin/bash
#
# Sample Applications Management Script
# Usage: samples.sh {start|stop|status|restart} [fix|ouch|all]
#
# Port assignments:
#   FIX Acceptor:  9876 (FIX protocol), 8081 (Admin API)
#   OUCH Acceptor: 9200 (OUCH protocol), 8082 (Admin API)
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
        echo "FIX acceptor failed to start. Check logs/acceptor.log"
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
        echo "OUCH acceptor failed to start. Check logs/acceptor.log"
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
            all) status_fix; echo ""; status_ouch ;;
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
        echo "Sample Applications Management Script"
        echo ""
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
        echo ""
        echo "Port assignments:"
        echo "  FIX Acceptor:  9876 (FIX protocol), 8081 (Admin API)"
        echo "  OUCH Acceptor: 9200 (OUCH protocol), 8082 (Admin API)"
        exit 1
        ;;
esac
