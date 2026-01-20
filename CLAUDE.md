# Claude Code Project Notes

## Latency Testing

Run latency tests using the script in the project root:

```bash
# Windows
run-latency-test.bat [warmup-orders] [test-orders] [rate]

# Linux/Mac
./run-latency-test.sh [warmup-orders] [test-orders] [rate]
```

Default parameters:
- warmup-orders: 10000
- test-orders: 1000
- rate: 100 orders/sec

Example:
```bash
run-latency-test.bat 5000 1000 100
```

The script:
1. Starts the FIX acceptor in background
2. Runs the initiator in latency mode
3. Displays latency statistics (min, max, avg, p50, p90, p95, p99, p99.9)
4. Cleans up processes when complete

Prerequisites: Run `mvn install -DskipTests` first to build the uber jar.

## Build Commands

```bash
# Build all modules
mvn install -DskipTests

# Run tests
mvn test

# Build specific module
mvn install -DskipTests -pl fix-engine
```
