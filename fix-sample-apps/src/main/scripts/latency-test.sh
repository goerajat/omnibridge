#!/bin/bash
# Run latency test: launches acceptor in latency mode, then initiator in latency mode
# Usage: latency-test.sh [initiator-options]
#
# Default test configuration:
#   Acceptor: port 9876, latency mode enabled, 100% fill rate
#   Initiator: connects to localhost:9876, latency mode enabled
#
# Example:
#   ./latency-test.sh
#   ./latency-test.sh --warmup-orders 5000 --test-orders 500 --rate 200

set -e

# Determine script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"
LIB_DIR="$APP_HOME/lib"
CONF_DIR="$APP_HOME/conf"

echo "============================================================"
echo "FIX Engine Latency Test"
echo "============================================================"
echo

# Build classpath from lib directory
CP=""
for jar in "$LIB_DIR"/*.jar; do
    if [ -z "$CP" ]; then
        CP="$jar"
    else
        CP="$CP:$jar"
    fi
done

# Add conf directory to classpath for logback.xml
if [ -d "$CONF_DIR" ]; then
    CP="$CONF_DIR:$CP"
fi

# JVM options for low latency
JVM_OPTS="${JVM_OPTS:--Xms256m -Xmx512m}"
JVM_OPTS="$JVM_OPTS -XX:+UseG1GC"
JVM_OPTS="$JVM_OPTS -XX:MaxGCPauseMillis=10"

# Cleanup function to kill acceptor on exit
ACCEPTOR_PID=""
cleanup() {
    if [ -n "$ACCEPTOR_PID" ] && kill -0 "$ACCEPTOR_PID" 2>/dev/null; then
        echo "Stopping acceptor (PID: $ACCEPTOR_PID)..."
        kill "$ACCEPTOR_PID" 2>/dev/null || true
        wait "$ACCEPTOR_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

# Start acceptor in background
echo "Starting FIX Acceptor in latency mode (background)..."
java $JVM_OPTS -cp "$CP" com.fixengine.samples.acceptor.SampleAcceptor --latency --fill-rate 1.0 &
ACCEPTOR_PID=$!

# Wait for acceptor to start listening
echo "Waiting for acceptor to start (PID: $ACCEPTOR_PID)..."
sleep 3

# Check if acceptor is still running
if ! kill -0 "$ACCEPTOR_PID" 2>/dev/null; then
    echo "ERROR: Acceptor failed to start!"
    exit 1
fi

echo
echo "Starting FIX Initiator in latency mode..."
echo "============================================================"
echo

# Run initiator in foreground
set +e
java $JVM_OPTS -cp "$CP" com.fixengine.samples.initiator.SampleInitiator --latency "$@"
INITIATOR_EXIT_CODE=$?
set -e

echo
echo "============================================================"

# Report result
echo
if [ $INITIATOR_EXIT_CODE -eq 0 ]; then
    echo "============================================================"
    echo "LATENCY TEST COMPLETED SUCCESSFULLY"
    echo "============================================================"
else
    echo "============================================================"
    echo "LATENCY TEST FAILED (exit code: $INITIATOR_EXIT_CODE)"
    echo "============================================================"
fi

exit $INITIATOR_EXIT_CODE
