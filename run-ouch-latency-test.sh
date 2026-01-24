#!/bin/bash

# ==========================================================================
# OUCH Latency Test Script for Development Environment
# Runs OUCH acceptor in background, then initiator in latency mode
# Usage: run-ouch-latency-test.sh [warmup-orders] [test-orders] [rate]
# ==========================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

UBER_JAR="apps/ouch-samples/target/ouch-samples-1.0.0-SNAPSHOT-all.jar"
OUCH_PORT=9200

# Check if uber jar exists
if [ ! -f "$UBER_JAR" ]; then
    echo "ERROR: Uber jar not found at $UBER_JAR"
    echo "Please run 'mvn install -DskipTests' first."
    exit 1
fi

# Default parameters
WARMUP_ORDERS=${1:-10000}
TEST_ORDERS=${2:-1000}
RATE=${3:-100}

echo "=========================================================================="
echo "OUCH Protocol Latency Test"
echo "=========================================================================="
echo "Uber JAR: $UBER_JAR"
echo "Port: $OUCH_PORT"
echo "Warmup Orders: $WARMUP_ORDERS"
echo "Test Orders: $TEST_ORDERS"
echo "Rate: $RATE orders/sec"
echo "=========================================================================="

# JVM options for low latency
# -Dagrona.disable.bounds.checks=true removes bounds checking from UnsafeBuffer for max performance
JVM_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=10 -XX:+AlwaysPreTouch -Dagrona.disable.bounds.checks=true"

ACCEPTOR_PID=""

# Cleanup function
cleanup() {
    echo ""
    echo "Stopping OUCH acceptor..."
    if [ -n "$ACCEPTOR_PID" ] && kill -0 "$ACCEPTOR_PID" 2>/dev/null; then
        kill "$ACCEPTOR_PID" 2>/dev/null || true
        sleep 1
        kill -9 "$ACCEPTOR_PID" 2>/dev/null || true
    fi
    # Also kill any process on the OUCH port
    if command -v lsof &> /dev/null; then
        lsof -ti:$OUCH_PORT 2>/dev/null | xargs -r kill -9 2>/dev/null || true
    elif command -v fuser &> /dev/null; then
        fuser -k $OUCH_PORT/tcp 2>/dev/null || true
    fi
}

trap cleanup EXIT

# Start OUCH acceptor in background (uber jar default main class is acceptor)
echo ""
echo "Starting OUCH Acceptor in background..."
java $JVM_OPTS -jar "$UBER_JAR" --latency > ouch-acceptor.log 2>&1 &
ACCEPTOR_PID=$!

# Wait for acceptor to start (check if port is listening)
echo "Waiting for acceptor to start..."
ACCEPTOR_READY=0
for i in {1..30}; do
    if nc -z localhost $OUCH_PORT 2>/dev/null; then
        ACCEPTOR_READY=1
        echo "Acceptor is ready on port $OUCH_PORT"
        break
    fi
    # Fallback check using /dev/tcp (bash built-in)
    if (echo >/dev/tcp/localhost/$OUCH_PORT) 2>/dev/null; then
        ACCEPTOR_READY=1
        echo "Acceptor is ready on port $OUCH_PORT"
        break
    fi
    sleep 1
done

if [ $ACCEPTOR_READY -eq 0 ]; then
    echo "ERROR: Acceptor failed to start within 30 seconds"
    echo "Check ouch-acceptor.log for details"
    cat ouch-acceptor.log
    exit 1
fi

# Small delay to ensure acceptor is fully initialized
sleep 2

# Run OUCH initiator in latency mode
echo ""
echo "Starting OUCH Initiator in latency mode..."
echo ""

TEST_EXIT_CODE=0
java $JVM_OPTS -cp "$UBER_JAR" com.fixengine.apps.ouch.initiator.SampleOuchInitiator --latency --warmup-orders $WARMUP_ORDERS --test-orders $TEST_ORDERS --rate $RATE || TEST_EXIT_CODE=$?

echo "=========================================================================="
if [ $TEST_EXIT_CODE -eq 0 ]; then
    echo "OUCH Latency test completed successfully"
else
    echo "OUCH Latency test completed with errors (exit code: $TEST_EXIT_CODE)"
fi
echo "=========================================================================="
echo "Acceptor log saved to: ouch-acceptor.log"
echo "=========================================================================="

exit $TEST_EXIT_CODE
