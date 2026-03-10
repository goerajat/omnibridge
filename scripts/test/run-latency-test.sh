#!/bin/bash

# ==========================================================================
# Latency Test Script for Development Environment
# Runs acceptor in background, then initiator in latency mode.
# Optionally monitors acceptor GC/heap usage via jstat.
#
# Usage: run-latency-test.sh [warmup-orders] [test-orders] [rate] [--gc]
#
# The --gc flag enables jstat heap monitoring of the acceptor JVM during the
# test run. A summary is printed at the end showing heap growth, GC counts,
# and GC pause times.
# ==========================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/../.."

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
GC_MONITOR=false
GC_INTERVAL=2000  # jstat sampling interval in ms

# Parse flags from all arguments
for arg in "$@"; do
    case "$arg" in
        --gc) GC_MONITOR=true ;;
    esac
done

echo "=========================================================================="
echo "FIX Engine Latency Test"
echo "=========================================================================="
echo "Uber JAR: $UBER_JAR"
echo "Warmup Orders: $WARMUP_ORDERS"
echo "Test Orders: $TEST_ORDERS"
echo "Rate: $RATE orders/sec"
echo "GC Monitoring: $GC_MONITOR"
echo "=========================================================================="

# JVM options for low latency
# -Dagrona.disable.bounds.checks=true removes bounds checking from UnsafeBuffer for max performance
JVM_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=10 -XX:+AlwaysPreTouch -Dagrona.disable.bounds.checks=true"

# Add GC logging when monitoring is enabled
GC_LOG_OPTS=""
if [ "$GC_MONITOR" = true ]; then
    GC_LOG_OPTS="-Xlog:gc*:file=acceptor-gc.log:time,uptime,level,tags:filecount=1,filesize=10m"
fi

# JVM options for Chronicle Queue (Java 17+)
CHRONICLE_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-exports java.base/jdk.internal.misc=ALL-UNNAMED --add-exports java.base/jdk.internal.ref=ALL-UNNAMED --add-exports java.base/sun.nio.ch=ALL-UNNAMED"

ACCEPTOR_PID=""
JSTAT_PID=""

# Cleanup function
cleanup() {
    echo ""
    # Stop jstat if running
    if [ -n "$JSTAT_PID" ] && kill -0 "$JSTAT_PID" 2>/dev/null; then
        kill "$JSTAT_PID" 2>/dev/null || true
    fi
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
java $JVM_OPTS $GC_LOG_OPTS $CHRONICLE_OPTS -jar "$UBER_JAR" -c "$CONFIG_DIR/latency-acceptor.conf" --latency > acceptor.log 2>&1 &
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

# Start jstat GC monitoring on acceptor if enabled
GC_LOG="acceptor-jstat.log"
if [ "$GC_MONITOR" = true ]; then
    if command -v jstat &> /dev/null; then
        echo "Starting jstat GC monitoring (PID=$ACCEPTOR_PID, interval=${GC_INTERVAL}ms)..."
        # -gcutil: GC utilization percentages and counts
        #   S0 S1 E O M CCS YGC YGCT FGC FGCT CGC CGCT GCT
        jstat -gcutil "$ACCEPTOR_PID" "$GC_INTERVAL" > "$GC_LOG" 2>&1 &
        JSTAT_PID=$!
        # Also capture a baseline heap snapshot
        echo "--- Baseline heap snapshot ---" > acceptor-heap.log
        jstat -gc "$ACCEPTOR_PID" >> acceptor-heap.log 2>&1
    else
        echo "WARNING: jstat not found on PATH. GC monitoring disabled."
        echo "Ensure JAVA_HOME/bin is on PATH."
        GC_MONITOR=false
    fi
fi

# Run initiator in latency mode (use -cp to specify different main class)
echo ""
echo "Starting FIX Initiator in latency mode..."
echo ""

TEST_EXIT_CODE=0
java $JVM_OPTS $CHRONICLE_OPTS -cp "$UBER_JAR" com.omnibridge.apps.fix.initiator.SampleInitiator -c "$CONFIG_DIR/latency-initiator.conf" --latency --warmup-orders $WARMUP_ORDERS --test-orders $TEST_ORDERS --rate $RATE || TEST_EXIT_CODE=$?

# Capture final heap snapshot before stopping
if [ "$GC_MONITOR" = true ] && [ -n "$JSTAT_PID" ]; then
    # Stop jstat
    kill "$JSTAT_PID" 2>/dev/null || true
    wait "$JSTAT_PID" 2>/dev/null || true
    JSTAT_PID=""

    # Final heap snapshot
    echo "--- Final heap snapshot ---" >> acceptor-heap.log
    jstat -gc "$ACCEPTOR_PID" >> acceptor-heap.log 2>&1
fi

echo "=========================================================================="
if [ $TEST_EXIT_CODE -eq 0 ]; then
    echo "Latency test completed successfully"
else
    echo "Latency test completed with errors (exit code: $TEST_EXIT_CODE)"
fi
echo "=========================================================================="
echo "Acceptor log saved to: acceptor.log"

# Print GC summary
if [ "$GC_MONITOR" = true ] && [ -f "$GC_LOG" ]; then
    echo "=========================================================================="
    echo "ACCEPTOR GC SUMMARY"
    echo "=========================================================================="
    echo "jstat log saved to: $GC_LOG"
    echo "Heap snapshots saved to: acceptor-heap.log"
    if [ -f "acceptor-gc.log" ]; then
        echo "GC event log saved to: acceptor-gc.log"
    fi
    echo ""

    # Parse jstat -gcutil output for summary
    # Header: S0 S1 E O M CCS YGC YGCT FGC FGCT CGC CGCT GCT
    LINES=$(grep -v '^\s*S0' "$GC_LOG" | grep -v '^\s*$' | wc -l)
    if [ "$LINES" -gt 0 ]; then
        # First and last data lines
        FIRST_LINE=$(grep -v '^\s*S0' "$GC_LOG" | grep -v '^\s*$' | head -1)
        LAST_LINE=$(grep -v '^\s*S0' "$GC_LOG" | grep -v '^\s*$' | tail -1)

        # Extract fields (YGC=7th, YGCT=8th, FGC=9th, FGCT=10th, CGC=11th, CGCT=12th, GCT=13th)
        FIRST_YGC=$(echo "$FIRST_LINE" | awk '{print $7}')
        FIRST_FGC=$(echo "$FIRST_LINE" | awk '{print $9}')
        FIRST_GCT=$(echo "$FIRST_LINE" | awk '{print $13}')
        LAST_YGC=$(echo "$LAST_LINE" | awk '{print $7}')
        LAST_YGCT=$(echo "$LAST_LINE" | awk '{print $8}')
        LAST_FGC=$(echo "$LAST_LINE" | awk '{print $9}')
        LAST_FGCT=$(echo "$LAST_LINE" | awk '{print $10}')
        LAST_CGC=$(echo "$LAST_LINE" | awk '{print $11}')
        LAST_CGCT=$(echo "$LAST_LINE" | awk '{print $12}')
        LAST_GCT=$(echo "$LAST_LINE" | awk '{print $13}')

        # Old gen utilization over time (column 4)
        FIRST_OLD=$(echo "$FIRST_LINE" | awk '{print $4}')
        LAST_OLD=$(echo "$LAST_LINE" | awk '{print $4}')
        MAX_OLD=$(grep -v '^\s*S0' "$GC_LOG" | grep -v '^\s*$' | awk '{print $4}' | sort -n | tail -1)

        # Eden utilization (column 3)
        MAX_EDEN=$(grep -v '^\s*S0' "$GC_LOG" | grep -v '^\s*$' | awk '{print $3}' | sort -n | tail -1)

        # GC counts during test
        YGC_DURING=$((${LAST_YGC:-0} - ${FIRST_YGC:-0}))
        FGC_DURING=$((${LAST_FGC:-0} - ${FIRST_FGC:-0}))
        GCT_DURING=$(echo "${LAST_GCT:-0} - ${FIRST_GCT:-0}" | bc 2>/dev/null || echo "N/A")

        echo "Samples collected: $LINES (every ${GC_INTERVAL}ms)"
        echo ""
        echo "--- Heap Utilization (%) ---"
        echo "  Old Gen:  start=${FIRST_OLD}%  end=${LAST_OLD}%  max=${MAX_OLD}%"
        echo "  Eden max: ${MAX_EDEN}%"
        echo ""
        echo "--- GC Activity During Test ---"
        echo "  Young GC count:       $YGC_DURING"
        echo "  Young GC time:        ${LAST_YGCT}s (cumulative)"
        echo "  Full GC count:        $FGC_DURING"
        echo "  Full GC time:         ${LAST_FGCT}s (cumulative)"
        echo "  Concurrent GC count:  ${LAST_CGC:-0}"
        echo "  Concurrent GC time:   ${LAST_CGCT:-0}s (cumulative)"
        echo "  Total GC time:        ${GCT_DURING}s (during test)"
        echo ""

        # Heap growth warning
        if [ -n "$FIRST_OLD" ] && [ -n "$LAST_OLD" ]; then
            OLD_GROWTH=$(echo "$LAST_OLD - $FIRST_OLD" | bc 2>/dev/null || echo "0")
            if [ "$(echo "$OLD_GROWTH > 10" | bc 2>/dev/null)" = "1" ] 2>/dev/null; then
                echo "WARNING: Old gen grew by ${OLD_GROWTH}% during the test."
                echo "  This may indicate a memory leak or insufficient heap sizing."
            elif [ "$FGC_DURING" -gt 0 ] 2>/dev/null; then
                echo "WARNING: $FGC_DURING full GC(s) occurred during the test."
                echo "  Full GCs cause long pauses — consider increasing heap size."
            else
                echo "OK: No significant heap growth or full GCs detected."
            fi
        fi
    else
        echo "No jstat data collected (test may have been too short)."
    fi
    echo ""

    # Print raw heap snapshots
    echo "--- Raw Heap Snapshots (jstat -gc) ---"
    cat acceptor-heap.log
fi
echo "=========================================================================="

exit $TEST_EXIT_CODE
