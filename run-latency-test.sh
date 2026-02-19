#!/bin/bash

# ==========================================================================
# Latency Test Script for Development Environment
# Runs acceptor in background, then initiator in latency mode
# Usage: run-latency-test.sh [warmup-orders] [test-orders] [rate]
# ==========================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

UBER_JAR=$(ls apps/fix-samples/target/fix-samples-*-all.jar 2>/dev/null | head -1)
CONFIG_DIR="apps/fix-samples/src/main/resources"

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
echo "FIX Engine Latency Test"
echo "=========================================================================="
echo "Uber JAR: $UBER_JAR"
echo "Warmup Orders: $WARMUP_ORDERS"
echo "Test Orders: $TEST_ORDERS"
echo "Rate: $RATE orders/sec"
echo "=========================================================================="

# JVM options for low latency
# -Dagrona.disable.bounds.checks=true removes bounds checking from UnsafeBuffer for max performance
JVM_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=10 -XX:+AlwaysPreTouch -Dagrona.disable.bounds.checks=true"

# JVM options for Chronicle Queue (Java 17+)
CHRONICLE_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-exports java.base/jdk.internal.misc=ALL-UNNAMED --add-exports java.base/jdk.internal.ref=ALL-UNNAMED --add-exports java.base/sun.nio.ch=ALL-UNNAMED"

ACCEPTOR_PID=""

# Cleanup function
cleanup() {
    echo ""
    echo "Stopping acceptor..."
    if [ -n "$ACCEPTOR_PID" ] && kill -0 "$ACCEPTOR_PID" 2>/dev/null; then
        kill "$ACCEPTOR_PID" 2>/dev/null || true
        sleep 1
        kill -9 "$ACCEPTOR_PID" 2>/dev/null || true
    fi
    # Also kill any process on port 9876
    if command -v lsof &> /dev/null; then
        lsof -ti:9876 2>/dev/null | xargs -r kill -9 2>/dev/null || true
    elif command -v fuser &> /dev/null; then
        fuser -k 9876/tcp 2>/dev/null || true
    fi
}

trap cleanup EXIT

# Start acceptor in background (uber jar default main class is acceptor)
echo ""
echo "Starting FIX Acceptor in background..."
java $JVM_OPTS $CHRONICLE_OPTS -jar "$UBER_JAR" -c "$CONFIG_DIR/latency-acceptor.conf" --latency > acceptor.log 2>&1 &
ACCEPTOR_PID=$!

# Wait for acceptor to start (check if port is listening)
echo "Waiting for acceptor to start..."
ACCEPTOR_READY=0
for i in {1..30}; do
    if nc -z localhost 9876 2>/dev/null; then
        ACCEPTOR_READY=1
        echo "Acceptor is ready on port 9876"
        break
    fi
    # Fallback check using /dev/tcp (bash built-in)
    if (echo >/dev/tcp/localhost/9876) 2>/dev/null; then
        ACCEPTOR_READY=1
        echo "Acceptor is ready on port 9876"
        break
    fi
    sleep 1
done

if [ $ACCEPTOR_READY -eq 0 ]; then
    echo "ERROR: Acceptor failed to start within 30 seconds"
    echo "Check acceptor.log for details"
    cat acceptor.log
    exit 1
fi

# Small delay to ensure acceptor is fully initialized
sleep 2

# Run initiator in latency mode (use -cp to specify different main class)
echo ""
echo "Starting FIX Initiator in latency mode..."
echo ""

TEST_EXIT_CODE=0
java $JVM_OPTS $CHRONICLE_OPTS -cp "$UBER_JAR" com.omnibridge.apps.fix.initiator.SampleInitiator -c "$CONFIG_DIR/latency-initiator.conf" --latency --warmup-orders $WARMUP_ORDERS --test-orders $TEST_ORDERS --rate $RATE || TEST_EXIT_CODE=$?

echo "=========================================================================="
if [ $TEST_EXIT_CODE -eq 0 ]; then
    echo "Latency test completed successfully"
else
    echo "Latency test completed with errors (exit code: $TEST_EXIT_CODE)"
fi
echo "=========================================================================="
echo "Acceptor log saved to: acceptor.log"
echo "=========================================================================="

exit $TEST_EXIT_CODE
