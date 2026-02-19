#!/bin/bash
# ==========================================================================
# OUCH 5.0 Latency Test Script for Development Environment
# Runs OUCH 5.0 acceptor in background, then initiator in latency mode
# Usage: ./run-ouch-latency-test-v50.sh [warmup-orders] [test-orders] [rate]
# ==========================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

UBER_JAR="apps/ouch-samples/target/ouch-samples-1.0.0-SNAPSHOT-all.jar"
OUCH_PORT=9250

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
echo "OUCH 5.0 Protocol Latency Test"
echo "=========================================================================="
echo "Uber JAR: $UBER_JAR"
echo "Port: $OUCH_PORT"
echo "Protocol: OUCH 5.0"
echo "Warmup Orders: $WARMUP_ORDERS"
echo "Test Orders: $TEST_ORDERS"
echo "Rate: $RATE orders/sec"
echo "=========================================================================="

# JVM options for low latency
JVM_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=10 -XX:+AlwaysPreTouch -Dagrona.disable.bounds.checks=true"

# JVM options for Chronicle Queue (Java 17+)
CHRONICLE_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-exports java.base/jdk.internal.misc=ALL-UNNAMED --add-exports java.base/jdk.internal.ref=ALL-UNNAMED --add-exports java.base/sun.nio.ch=ALL-UNNAMED"

# Cleanup function
cleanup() {
    echo ""
    echo "Stopping OUCH 5.0 acceptor..."
    if [ -n "$ACCEPTOR_PID" ] && kill -0 "$ACCEPTOR_PID" 2>/dev/null; then
        kill "$ACCEPTOR_PID" 2>/dev/null || true
        wait "$ACCEPTOR_PID" 2>/dev/null || true
    fi
    # Also kill any process on the port
    if command -v lsof &> /dev/null; then
        lsof -ti:$OUCH_PORT | xargs -r kill -9 2>/dev/null || true
    fi
}
trap cleanup EXIT

# Start OUCH 5.0 acceptor in background
echo ""
echo "Starting OUCH 5.0 Acceptor in background..."
java $JVM_OPTS $CHRONICLE_OPTS -jar "$UBER_JAR" -c ouch-acceptor-v50.conf --latency > ouch-acceptor-v50.log 2>&1 &
ACCEPTOR_PID=$!

# Wait for acceptor to start
echo "Waiting for acceptor to start..."
ACCEPTOR_READY=0
for i in {1..30}; do
    if nc -z localhost $OUCH_PORT 2>/dev/null; then
        ACCEPTOR_READY=1
        echo "Acceptor is ready on port $OUCH_PORT"
        break
    fi
    sleep 0.5
done

if [ $ACCEPTOR_READY -eq 0 ]; then
    echo "ERROR: Acceptor failed to start within 30 seconds"
    echo "Check ouch-acceptor-v50.log for details"
    cat ouch-acceptor-v50.log
    exit 1
fi

# Small delay to ensure acceptor is fully initialized
sleep 2

# Run OUCH 5.0 initiator in latency mode
echo ""
echo "Starting OUCH 5.0 Initiator in latency mode..."
echo ""
java $JVM_OPTS $CHRONICLE_OPTS -cp "$UBER_JAR" com.omnibridge.apps.ouch.initiator.SampleOuchInitiator \
    -c ouch-initiator-v50.conf \
    --latency \
    --warmup-orders $WARMUP_ORDERS \
    --test-orders $TEST_ORDERS \
    --rate $RATE

TEST_EXIT_CODE=$?

echo "=========================================================================="
if [ $TEST_EXIT_CODE -eq 0 ]; then
    echo "OUCH 5.0 Latency test completed successfully"
else
    echo "OUCH 5.0 Latency test completed with errors"
fi
echo "=========================================================================="
echo "Acceptor log saved to: ouch-acceptor-v50.log"
echo "=========================================================================="

exit $TEST_EXIT_CODE
