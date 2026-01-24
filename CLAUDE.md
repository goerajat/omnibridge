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

## OUCH Latency Testing

Run OUCH latency tests:

```bash
# OUCH 4.2
run-ouch-latency-test.bat [warmup-orders] [test-orders] [rate]

# OUCH 5.0
run-ouch-latency-test-v50.bat [warmup-orders] [test-orders] [rate]
```

## Architecture Documentation

**IMPORTANT**: The protocol architecture is documented in `docs/PROTOCOL_ARCHITECTURE.md`. This document serves as a template for implementing new exchange protocols.

### When to Update Architecture Documentation

Update `docs/PROTOCOL_ARCHITECTURE.md` when making changes to:

1. **Module Structure**
   - Adding/removing Maven modules
   - Changing module dependencies
   - Restructuring package layout

2. **Message Encoding/Decoding**
   - Changing flyweight patterns
   - Adding new base message classes
   - Modifying buffer handling

3. **Network Layer**
   - Changes to TcpChannel ring buffer mechanism
   - NetworkEventLoop modifications
   - New network handler patterns

4. **Session Management**
   - State machine changes
   - New session types
   - Scheduler modifications

5. **Configuration Framework**
   - Component lifecycle changes
   - ComponentProvider modifications
   - New configuration patterns

6. **Multi-Version Support**
   - Adding new protocol versions
   - Changing version-specific dispatch patterns
   - New appendage types

7. **Testing Infrastructure**
   - New test script patterns
   - Test framework changes
   - New latency tracking methods

### Documentation Review Checklist

When modifying the architecture, verify:
- [ ] All new classes are documented with their purpose
- [ ] File paths in appendices are accurate
- [ ] Code examples reflect current implementation
- [ ] New patterns are added to relevant sections
- [ ] Multi-version handling is documented if applicable
- [ ] Test procedures are updated if changed
