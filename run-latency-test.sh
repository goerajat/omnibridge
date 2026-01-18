#!/bin/bash
# Run latency test: launches acceptor in latency mode, then initiator in latency mode
# Usage: ./run-latency-test.sh [initiator-options]
#
# Default test configuration:
#   Acceptor: port 9876, latency mode enabled
#   Initiator: connects to localhost:9876, latency mode enabled
#
# Example:
#   ./run-latency-test.sh
#   ./run-latency-test.sh --warmup-orders 5000 --test-orders 500 --rate 200

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DIR="$SCRIPT_DIR/fix-sample-apps/target"

echo "============================================================"
echo "FIX Engine Latency Test"
echo "============================================================"
echo

# Check if compiled
if [ ! -d "$TARGET_DIR/classes" ]; then
    echo "Project not compiled. Running mvn compile..."
    cd "$SCRIPT_DIR"
    mvn compile -q
fi

# Build classpath
CP="$TARGET_DIR/classes"
CP="$CP:$SCRIPT_DIR/fix-engine/target/classes"
CP="$CP:$SCRIPT_DIR/fix-message/target/classes"
CP="$CP:$SCRIPT_DIR/fix-network-io/target/classes"
CP="$CP:$SCRIPT_DIR/fix-persistence/target/classes"

# Add Maven dependencies from local repo
M2_REPO="$HOME/.m2/repository"
CP="$CP:$M2_REPO/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar"
CP="$CP:$M2_REPO/ch/qos/logback/logback-classic/1.4.14/logback-classic-1.4.14.jar"
CP="$CP:$M2_REPO/ch/qos/logback/logback-core/1.4.14/logback-core-1.4.14.jar"
CP="$CP:$M2_REPO/info/picocli/picocli/4.7.5/picocli-4.7.5.jar"
CP="$CP:$M2_REPO/com/fasterxml/jackson/core/jackson-databind/2.16.1/jackson-databind-2.16.1.jar"
CP="$CP:$M2_REPO/com/fasterxml/jackson/core/jackson-core/2.16.1/jackson-core-2.16.1.jar"
CP="$CP:$M2_REPO/com/fasterxml/jackson/core/jackson-annotations/2.16.1/jackson-annotations-2.16.1.jar"
CP="$CP:$M2_REPO/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar"
CP="$CP:$M2_REPO/net/java/dev/jna/jna-platform/5.14.0/jna-platform-5.14.0.jar"
CP="$CP:$M2_REPO/org/agrona/agrona/1.20.0/agrona-1.20.0.jar"

# Cleanup function to kill acceptor on exit
ACCEPTOR_PID=""
cleanup() {
    if [ -n "$ACCEPTOR_PID" ] && kill -0 "$ACCEPTOR_PID" 2>/dev/null; then
        echo "Stopping acceptor (PID: $ACCEPTOR_PID)..."
        kill "$ACCEPTOR_PID" 2>/dev/null || true
        wait "$ACCEPTOR_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# Start acceptor in background
echo "Starting FIX Acceptor in latency mode (background)..."
java -cp "$CP" com.fixengine.samples.acceptor.SampleAcceptor --latency --fill-rate 1.0 &
ACCEPTOR_PID=$!

# Wait for acceptor to start listening
echo "Waiting for acceptor to start..."
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
java -cp "$CP" com.fixengine.samples.initiator.SampleInitiator --latency "$@"
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
