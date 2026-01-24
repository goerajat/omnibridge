#!/bin/bash
# OUCH Latency Test script
# Starts acceptor, runs latency test with initiator, then cleans up

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

WARMUP_ORDERS=${1:-1000}
TEST_ORDERS=${2:-1000}
RATE=${3:-100}

echo "OUCH Latency Test"
echo "================="
echo "Warmup orders: $WARMUP_ORDERS"
echo "Test orders: $TEST_ORDERS"
echo "Rate: $RATE orders/sec"
echo ""

# Start acceptor in background
echo "Starting OUCH Acceptor..."
"$SCRIPT_DIR/ouch-acceptor.sh" --latency &
ACCEPTOR_PID=$!
sleep 3

# Run initiator in latency mode
echo "Running latency test..."
"$SCRIPT_DIR/ouch-initiator.sh" --latency \
    --warmup-orders $WARMUP_ORDERS \
    --test-orders $TEST_ORDERS \
    --rate $RATE

# Cleanup
echo ""
echo "Stopping acceptor..."
kill $ACCEPTOR_PID 2>/dev/null
wait $ACCEPTOR_PID 2>/dev/null

echo "Done."
