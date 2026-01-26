#!/bin/bash

# ==========================================================================
# Reference Test Script
# Runs sample acceptor, then reference tester (QuickFIX/J) against it
# Usage: run-reference-test.sh [test-names]
#   test-names: comma-separated test names or 'all' (default: all)
# ==========================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

ACCEPTOR_JAR="apps/fix-samples/target/fix-samples-1.0.0-SNAPSHOT-all.jar"
TESTER_JAR="protocols/fix/reference-tester/target/reference-tester-1.0.0-SNAPSHOT-all.jar"
CONFIG_DIR="apps/fix-samples/src/main/resources"

# Check if jars exist
if [ ! -f "$ACCEPTOR_JAR" ]; then
    echo "ERROR: Acceptor jar not found at $ACCEPTOR_JAR"
    echo "Please run 'mvn install -DskipTests' first."
    exit 1
fi
if [ ! -f "$TESTER_JAR" ]; then
    echo "ERROR: Reference tester jar not found at $TESTER_JAR"
    echo "Please run 'mvn install -DskipTests' first."
    exit 1
fi

# Default parameters
TESTS=${1:-all}

echo "=========================================================================="
echo "FIX Reference Test Suite"
echo "=========================================================================="
echo "Acceptor JAR: $ACCEPTOR_JAR"
echo "Tester JAR: $TESTER_JAR"
echo "Tests: $TESTS"
echo "=========================================================================="

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

# Start acceptor in background
echo ""
echo "Starting FIX Acceptor in background..."
java -jar "$ACCEPTOR_JAR" -c "$CONFIG_DIR/acceptor.conf" > acceptor-ref-test.log 2>&1 &
ACCEPTOR_PID=$!

# Wait for acceptor to start
echo "Waiting for acceptor to start..."
ACCEPTOR_READY=0
for i in {1..30}; do
    if nc -z localhost 9876 2>/dev/null || (echo >/dev/tcp/localhost/9876) 2>/dev/null; then
        ACCEPTOR_READY=1
        echo "Acceptor is ready on port 9876"
        break
    fi
    sleep 1
done

if [ $ACCEPTOR_READY -eq 0 ]; then
    echo "ERROR: Acceptor failed to start within 30 seconds"
    echo "Check acceptor-ref-test.log for details"
    cat acceptor-ref-test.log
    exit 1
fi

# Small delay to ensure acceptor is fully initialized
sleep 2

# Run reference tester
echo ""
echo "Running Reference Tester (QuickFIX/J)..."
echo "=========================================================================="
echo ""

TEST_EXIT_CODE=0
java -jar "$TESTER_JAR" test --host localhost --port 9876 --sender CLIENT --target EXCHANGE --tests $TESTS || TEST_EXIT_CODE=$?

echo ""
echo "=========================================================================="
echo ""
echo "SUMMARY"
echo "=========================================================================="
if [ $TEST_EXIT_CODE -eq 0 ]; then
    echo "Result: ALL TESTS PASSED"
else
    echo "Result: SOME TESTS FAILED"
fi
echo "=========================================================================="
echo "Acceptor log: acceptor-ref-test.log"
echo "=========================================================================="

exit $TEST_EXIT_CODE
