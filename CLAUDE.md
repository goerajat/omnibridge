# Claude Code Project Notes

## Build

Always use Maven for builds:

```bash
mvn install -DskipTests    # Build all modules
mvn test                   # Run tests
mvn compile                # Compile only
```

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

## Reference Testing

Run reference tests using QuickFIX/J against the sample acceptor:

```bash
# Windows
run-reference-test.bat [test-names]

# Linux/Mac
./run-reference-test.sh [test-names]
```

Available tests: HeartbeatTest, SequenceNumberTest, NewOrderTest, MarketOrderTest, OrderCancelTest, OrderModifyTest, MultipleOrdersTest, LogonLogoutTest

Example:
```bash
run-reference-test.bat all
run-reference-test.bat HeartbeatTest,NewOrderTest
```

## Session Testing

Run session tests using our FIX engine against the sample acceptor:

```bash
# Windows
run-session-test.bat [test-names] [report-format]

# Linux/Mac
./run-session-test.sh [test-names] [report-format]
```

Available tests: LogonLogoutTest, SequenceNumberTest, TestRequestTest, HeartbeatTest, ResendRequestTest

Report formats: text (default), json, html

Example:
```bash
run-session-test.bat all text
run-session-test.bat HeartbeatTest json
```
