#!/bin/bash

# ==========================================================================
# Aeron Replication Test Script
# Runs FIX acceptor with Aeron persistence + standalone AeronRemoteStore,
# then runs reference/session tests, and validates both stores match.
#
# Usage: run-aeron-replication-test.sh [test-type] [test-names]
#   test-type: reference (default), session, or both
#   test-names: comma-separated test names or 'all' (default: all)
# ==========================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

ACCEPTOR_JAR=$(ls apps/fix-samples/target/fix-samples-*-all.jar 2>/dev/null | head -1)
REF_TESTER_JAR=$(ls protocols/fix/reference-tester/target/reference-tester-*-all.jar 2>/dev/null | head -1)
SESSION_TESTER_JAR=$(ls protocols/fix/session-tester/target/session-tester-*-all.jar 2>/dev/null | head -1)
CONFIG_DIR="apps/fix-samples/src/main/resources"

# Check if acceptor jar exists
if [ ! -f "$ACCEPTOR_JAR" ]; then
    echo "ERROR: Acceptor jar not found."
    echo "Please run 'mvn install -DskipTests' first."
    exit 1
fi

# Default parameters
TEST_TYPE=${1:-reference}
TESTS=${2:-all}

echo "=========================================================================="
echo "Aeron Replication Test Suite"
echo "=========================================================================="
echo "Acceptor JAR:     $ACCEPTOR_JAR"
echo "Test Type:        $TEST_TYPE"
echo "Tests:            $TESTS"
echo "=========================================================================="

ACCEPTOR_PID=""
REMOTE_STORE_PID=""

# Cleanup function
cleanup() {
    echo ""
    echo "Cleaning up processes..."
    if [ -n "$ACCEPTOR_PID" ] && kill -0 "$ACCEPTOR_PID" 2>/dev/null; then
        kill "$ACCEPTOR_PID" 2>/dev/null || true
        sleep 1
        kill -9 "$ACCEPTOR_PID" 2>/dev/null || true
    fi
    if [ -n "$REMOTE_STORE_PID" ] && kill -0 "$REMOTE_STORE_PID" 2>/dev/null; then
        kill "$REMOTE_STORE_PID" 2>/dev/null || true
        sleep 1
        kill -9 "$REMOTE_STORE_PID" 2>/dev/null || true
    fi
    # Also kill any process on port 9876
    if command -v lsof &> /dev/null; then
        lsof -ti:9876 2>/dev/null | xargs -r kill -9 2>/dev/null || true
    elif command -v fuser &> /dev/null; then
        fuser -k 9876/tcp 2>/dev/null || true
    fi
}

trap cleanup EXIT

# JVM options for Chronicle Queue (Java 17+)
CHRONICLE_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-exports java.base/jdk.internal.misc=ALL-UNNAMED --add-exports java.base/jdk.internal.ref=ALL-UNNAMED --add-exports java.base/sun.nio.ch=ALL-UNNAMED"

# Clean data directories
echo ""
echo "Cleaning data directories..."
rm -rf ./data/local-cache ./data/remote-store
mkdir -p ./data/local-cache ./data/remote-store

# Step 1: Start AeronRemoteStore
echo ""
echo "Starting AeronRemoteStore..."
java $CHRONICLE_OPTS -cp "$ACCEPTOR_JAR" com.omnibridge.persistence.aeron.AeronRemoteStoreMain -c "$CONFIG_DIR/remote-store.conf" > remote-store.log 2>&1 &
REMOTE_STORE_PID=$!
echo "AeronRemoteStore PID: $REMOTE_STORE_PID"

# Wait for remote store to initialize
sleep 3

# Verify remote store is still running
if ! kill -0 "$REMOTE_STORE_PID" 2>/dev/null; then
    echo "ERROR: AeronRemoteStore failed to start"
    echo "Check remote-store.log for details"
    cat remote-store.log
    exit 1
fi

# Step 2: Start FIX Acceptor with Aeron persistence
echo ""
echo "Starting FIX Acceptor with Aeron persistence..."
java $CHRONICLE_OPTS -jar "$ACCEPTOR_JAR" -c "$CONFIG_DIR/acceptor-aeron.conf" > acceptor-aeron-test.log 2>&1 &
ACCEPTOR_PID=$!
echo "Acceptor PID: $ACCEPTOR_PID"

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
    echo "Check acceptor-aeron-test.log for details"
    cat acceptor-aeron-test.log
    exit 1
fi

# Small delay to ensure full initialization
sleep 2

# Step 3: Run tests
TEST_EXIT_CODE=0

run_reference_tests() {
    if [ ! -f "$REF_TESTER_JAR" ]; then
        echo "WARNING: Reference tester jar not found, skipping reference tests."
        return 1
    fi
    echo ""
    echo "Running Reference Tests (QuickFIX/J)..."
    echo "=========================================================================="
    java $CHRONICLE_OPTS -jar "$REF_TESTER_JAR" test --host localhost --port 9876 --sender CLIENT --target EXCHANGE --tests $TESTS || return $?
}

run_session_tests() {
    if [ ! -f "$SESSION_TESTER_JAR" ]; then
        echo "WARNING: Session tester jar not found, skipping session tests."
        return 1
    fi
    echo ""
    echo "Running Session Tests (FIX Engine)..."
    echo "=========================================================================="
    java $CHRONICLE_OPTS -jar "$SESSION_TESTER_JAR" --host localhost --port 9876 --sender CLIENT --target EXCHANGE --tests $TESTS --report-format text || return $?
}

case "$TEST_TYPE" in
    reference)
        run_reference_tests || TEST_EXIT_CODE=$?
        ;;
    session)
        run_session_tests || TEST_EXIT_CODE=$?
        ;;
    both)
        run_reference_tests || TEST_EXIT_CODE=$?
        # Restart acceptor between test suites
        echo ""
        echo "Restarting acceptor for session tests..."
        kill "$ACCEPTOR_PID" 2>/dev/null || true
        sleep 2
        java $CHRONICLE_OPTS -jar "$ACCEPTOR_JAR" -c "$CONFIG_DIR/acceptor-aeron.conf" > acceptor-aeron-test.log 2>&1 &
        ACCEPTOR_PID=$!
        sleep 5
        run_session_tests || TEST_EXIT_CODE=$?
        ;;
    *)
        echo "ERROR: Unknown test type '$TEST_TYPE'. Use: reference, session, or both"
        exit 1
        ;;
esac

# Step 4: Wait for replication to drain
echo ""
echo "Waiting for replication to drain..."
sleep 5

# Step 5: Stop acceptor (remote store stays running briefly for validation)
echo "Stopping acceptor..."
kill "$ACCEPTOR_PID" 2>/dev/null || true
sleep 2

# Step 6: Stop remote store
echo "Stopping remote store..."
kill "$REMOTE_STORE_PID" 2>/dev/null || true
sleep 2

# Step 7: Validate both stores match
echo ""
echo "=========================================================================="
echo "Store Validation"
echo "=========================================================================="

VALIDATE_EXIT_CODE=0
java $CHRONICLE_OPTS -cp "$ACCEPTOR_JAR" com.omnibridge.persistence.aeron.StoreValidator \
    --local ./data/local-cache --remote ./data/remote-store --publisher-id 1 --fix-validate --verbose || VALIDATE_EXIT_CODE=$?

# Step 8: Report combined result
echo ""
echo "=========================================================================="
echo "COMBINED RESULT"
echo "=========================================================================="
echo "Test result:       $([ $TEST_EXIT_CODE -eq 0 ] && echo 'PASSED' || echo 'FAILED')"
echo "Validation result: $([ $VALIDATE_EXIT_CODE -eq 0 ] && echo 'PASSED' || echo 'FAILED')"

FINAL_EXIT_CODE=0
if [ $TEST_EXIT_CODE -ne 0 ] || [ $VALIDATE_EXIT_CODE -ne 0 ]; then
    FINAL_EXIT_CODE=1
    echo "OVERALL: FAILED"
else
    echo "OVERALL: PASSED"
fi
echo "=========================================================================="
echo "Logs: acceptor-aeron-test.log, remote-store.log"
echo "Data: ./data/local-cache, ./data/remote-store"
echo "=========================================================================="

exit $FINAL_EXIT_CODE
