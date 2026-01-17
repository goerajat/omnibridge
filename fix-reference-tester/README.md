# FIX Reference Tester

A reference FIX implementation using QuickFIX/J for interoperability testing with the custom FIX engine.

## Overview

The FIX Reference Tester provides three main capabilities:

1. **Reference Acceptor (Exchange Simulator)** - A QuickFIX/J-based FIX acceptor that simulates an exchange, receiving orders and sending execution reports. Use this to test your FIX initiator/client.

2. **Reference Initiator (Client Simulator)** - A QuickFIX/J-based FIX initiator that connects to a FIX acceptor and sends orders. Use this to test your FIX acceptor/server.

3. **Test Suite** - Automated tests that verify FIX protocol compliance including session management, order handling, and message sequencing.

## Prerequisites

- Java 17 or higher
- Maven 3.8+

## Building

### Build from the connectivity root directory

```bash
cd connectivity
mvn clean package -pl fix-reference-tester -am -DskipTests
```

### Build only the reference tester module

```bash
cd fix-reference-tester
mvn clean package -DskipTests
```

### Output

After building, two JAR files are created in `target/`:

| File | Description |
|------|-------------|
| `fix-reference-tester-1.0.0-SNAPSHOT.jar` | Library JAR (requires dependencies) |
| `fix-reference-tester-1.0.0-SNAPSHOT-all.jar` | Fat JAR with all dependencies (executable) |

## Running

Use the fat JAR for all operations:

```bash
java -jar fix-reference-tester/target/fix-reference-tester-1.0.0-SNAPSHOT-all.jar [command] [options]
```

### Quick Reference

```bash
# Show help
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar --help

# Run as exchange simulator (acceptor)
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar acceptor --port 9880

# Run as client (initiator) and send a test order
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar initiator --host localhost --port 9880 --send-order

# Run test suite
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar test --host localhost --port 9880 --tests all
```

---

## Command: `acceptor`

Run a reference FIX acceptor (exchange simulator) using QuickFIX/J.

### Usage

```bash
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar acceptor [options]
```

### Options

| Option | Description | Default |
|--------|-------------|---------|
| `-p, --port <port>` | Port to listen on | 9880 |
| `--sender <id>` | SenderCompID | EXCHANGE |
| `--target <id>` | TargetCompID | CLIENT |
| `--begin-string <ver>` | FIX version | FIX.4.4 |
| `--heartbeat <sec>` | Heartbeat interval in seconds | 30 |
| `--fill-rate <rate>` | Order fill rate 0.0-1.0 | 1.0 |
| `--fill-delay <ms>` | Delay before sending fill in ms | 0 |
| `--no-auto-ack` | Disable automatic execution reports | false |
| `--reset-on-logon` | Reset sequence numbers on logon | true |
| `--daemon` | Run in daemon mode (no interactive input) | false |

### Examples

```bash
# Basic acceptor on default port
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar acceptor

# Acceptor on custom port with 50% fill rate
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar acceptor \
  --port 9881 \
  --fill-rate 0.5

# Acceptor with delayed fills (simulating exchange latency)
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar acceptor \
  --port 9880 \
  --fill-delay 100

# Acceptor for FIX 4.2
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar acceptor \
  --port 9882 \
  --begin-string FIX.4.2

# Acceptor without automatic execution reports (manual testing)
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar acceptor \
  --no-auto-ack

# Acceptor in daemon mode (for background/service deployment)
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar acceptor \
  --port 9880 \
  --daemon
```

### Behavior

The acceptor automatically handles:
- **Logon/Logout** - Session establishment and termination
- **Heartbeat/TestRequest** - Session keepalive
- **NewOrderSingle (D)** - Responds with ExecutionReport (NEW, then FILLED or REJECTED)
- **OrderCancelRequest (F)** - Responds with ExecutionReport (CANCELED)
- **OrderCancelReplaceRequest (G)** - Responds with ExecutionReport (REPLACED)
- **OrderStatusRequest (H)** - Responds with ExecutionReport (ORDER_STATUS)

---

## Command: `initiator`

Run a reference FIX initiator (client simulator) using QuickFIX/J.

### Usage

```bash
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar initiator [options]
```

### Options

| Option | Description | Default |
|--------|-------------|---------|
| `-h, --host <host>` | Target host | localhost |
| `-p, --port <port>` | Target port | 9880 |
| `--sender <id>` | SenderCompID | CLIENT |
| `--target <id>` | TargetCompID | SERVER |
| `--begin-string <ver>` | FIX version | FIX.4.4 |
| `--heartbeat <sec>` | Heartbeat interval in seconds | 30 |
| `--reset-on-logon` | Reset sequence numbers on logon | true |
| `--interactive` | Interactive mode for manual order entry | false |
| `--send-order` | Send a test order after logon | false |
| `--symbol <sym>` | Symbol for test order | AAPL |
| `--side <side>` | Side: buy/sell | buy |
| `--qty <qty>` | Quantity for test order | 100 |
| `--price <price>` | Price for test order | 150.00 |

### Examples

```bash
# Connect and send a single test order
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar initiator \
  --host localhost \
  --port 9880 \
  --send-order

# Send a specific order
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar initiator \
  --host localhost \
  --port 9880 \
  --send-order \
  --symbol MSFT \
  --side sell \
  --qty 500 \
  --price 380.50

# Interactive mode
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar initiator \
  --host localhost \
  --port 9880 \
  --interactive

# Connect to custom CompIDs
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar initiator \
  --host exchange.example.com \
  --port 9880 \
  --sender MYCLIENT \
  --target EXCHANGE
```

### Interactive Mode Commands

When running with `--interactive`, the following commands are available:

| Command | Description |
|---------|-------------|
| `buy <symbol> <qty> <price>` | Send a buy limit order |
| `sell <symbol> <qty> <price>` | Send a sell limit order |
| `test` | Send a TestRequest |
| `logout` | Logout and exit |
| `quit` | Exit without logout |

Example interactive session:
```
> buy AAPL 100 150.00
Buy order sent: ORD-20240115093045123-1

> sell MSFT 50 380.00
Sell order sent: ORD-20240115093052456-2

> test
TestRequest sent: TEST-1705312256789

> logout
```

---

## Command: `test`

Run the automated test suite against a FIX acceptor.

### Usage

```bash
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar test [options]
```

### Options

| Option | Description | Default |
|--------|-------------|---------|
| `-h, --host <host>` | Target host | localhost |
| `-p, --port <port>` | Target port | 9880 |
| `--sender <id>` | SenderCompID | CLIENT |
| `--target <id>` | TargetCompID | SERVER |
| `--begin-string <ver>` | FIX version | FIX.4.4 |
| `-t, --tests <tests>` | Comma-separated test names or "all" | all |
| `-l, --list-tests` | List available tests and exit | - |
| `--timeout <sec>` | Default timeout in seconds | 30 |

### Available Tests

| Test Name | Description |
|-----------|-------------|
| `HeartbeatTest` | Tests FIX heartbeat mechanism by sending TestRequest and waiting for Heartbeat response |
| `SequenceNumberTest` | Tests sequence number tracking and increment behavior |
| `NewOrderTest` | Tests submitting a new limit order and receiving execution report |
| `MarketOrderTest` | Tests submitting a market order and receiving execution report |
| `OrderCancelTest` | Tests submitting an order and then cancelling it |
| `OrderModifyTest` | Tests submitting an order and modifying it via cancel/replace |
| `MultipleOrdersTest` | Tests submitting multiple orders in sequence and receiving all execution reports |
| `LogonLogoutTest` | Tests FIX session logon and logout sequence |

### Examples

```bash
# List available tests
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar test --list-tests

# Run all tests
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar test \
  --host localhost \
  --port 9880 \
  --tests all

# Run specific tests
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar test \
  --host localhost \
  --port 9880 \
  --tests HeartbeatTest,NewOrderTest,OrderCancelTest

# Run with custom CompIDs and timeout
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar test \
  --host localhost \
  --port 9880 \
  --sender TESTCLIENT \
  --target EXCHANGE \
  --timeout 60 \
  --tests all
```

### Sample Test Output

```
========================================
FIX Reference Tester - Test Suite
========================================
Target: localhost:9880
Tests: 8
========================================

----------------------------------------
Running test: HeartbeatTest
Description: Tests FIX heartbeat mechanism by sending TestRequest and waiting for Heartbeat response
Test HeartbeatTest: PASSED - Heartbeat test passed - received response to TestRequest

----------------------------------------
Running test: NewOrderTest
Description: Tests submitting a new limit order and receiving execution report
Test NewOrderTest: PASSED - Order submitted and execution report received - ExecType: 0

...

========================================
TEST SUITE SUMMARY
========================================

  ✓ PASS  HeartbeatTest                   Heartbeat test passed - received response to TestRequest
  ✓ PASS  SequenceNumberTest              Sequence numbers tracking correctly - incremented as expected
  ✓ PASS  NewOrderTest                    Order submitted and execution report received - ExecType: 0
  ✓ PASS  MarketOrderTest                 Market order processed - ExecType: 0
  ✓ PASS  OrderCancelTest                 Order submitted and cancelled successfully
  ✓ PASS  OrderModifyTest                 Order submitted and modified successfully
  ✓ PASS  MultipleOrdersTest              Sent 5 orders, received 5 execution reports
  ✓ PASS  LogonLogoutTest                 Logon and logout completed successfully

----------------------------------------
Total: 8 tests, 8 passed, 0 failed
Duration: 12.345 seconds
========================================
```

---

## Testing Scenarios

### Scenario 1: Test Your FIX Initiator Against Reference Acceptor

Use the reference acceptor to test your custom FIX initiator/client:

```bash
# Terminal 1: Start reference acceptor
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar acceptor --port 9880

# Terminal 2: Run your FIX initiator connecting to localhost:9880
# Your initiator should connect with:
#   TargetCompID: EXCHANGE
#   SenderCompID: CLIENT (or as configured)
```

### Scenario 2: Test Your FIX Acceptor Against Reference Initiator

Use the reference initiator to test your custom FIX acceptor/server:

```bash
# Terminal 1: Start your FIX acceptor on port 9880

# Terminal 2: Run reference initiator
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar initiator \
  --host localhost \
  --port 9880 \
  --sender CLIENT \
  --target YOUR_ACCEPTOR_COMPID \
  --interactive
```

### Scenario 3: Run Full Compliance Test Suite

Test your FIX acceptor against the full test suite:

```bash
# Terminal 1: Start your FIX acceptor on port 9880

# Terminal 2: Run test suite
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar test \
  --host localhost \
  --port 9880 \
  --sender CLIENT \
  --target YOUR_ACCEPTOR_COMPID \
  --tests all
```

### Scenario 4: Test Between Reference Components

Verify the reference implementation works correctly:

```bash
# Terminal 1: Start reference acceptor
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar acceptor \
  --port 9880 \
  --sender EXCHANGE \
  --target CLIENT

# Terminal 2: Run test suite against reference acceptor
java -jar fix-reference-tester-1.0.0-SNAPSHOT-all.jar test \
  --host localhost \
  --port 9880 \
  --sender CLIENT \
  --target EXCHANGE \
  --tests all
```

---

## Configuration Files

The module includes default QuickFIX/J configuration files in `src/main/resources/`:

### acceptor.cfg

```ini
[DEFAULT]
ConnectionType=acceptor
StartTime=00:00:00
EndTime=00:00:00
HeartBtInt=30
ReconnectInterval=5
FileStorePath=./quickfix-store
FileLogPath=./quickfix-logs
UseDataDictionary=Y
ResetOnLogon=Y

[SESSION]
BeginString=FIX.4.4
SenderCompID=EXCHANGE
TargetCompID=CLIENT
SocketAcceptPort=9880
DataDictionary=FIX44.xml
```

### initiator.cfg

```ini
[DEFAULT]
ConnectionType=initiator
StartTime=00:00:00
EndTime=00:00:00
HeartBtInt=30
ReconnectInterval=5
FileStorePath=./quickfix-store
FileLogPath=./quickfix-logs
UseDataDictionary=Y
ResetOnLogon=Y

[SESSION]
BeginString=FIX.4.4
SenderCompID=CLIENT
TargetCompID=SERVER
SocketConnectHost=localhost
SocketConnectPort=9880
DataDictionary=FIX44.xml
```

**Note:** When using command-line options, the programmatic configuration takes precedence over these files.

---

## Logging

Logs are written to:
- Console (INFO level)
- `./logs/reference-tester.log` (DEBUG level)

QuickFIX/J message logs are also available:
- Incoming messages: `quickfixj.msg.incoming`
- Outgoing messages: `quickfixj.msg.outgoing`

To enable verbose FIX message logging, modify `logback.xml`:

```xml
<logger name="quickfixj.msg.incoming" level="DEBUG"/>
<logger name="quickfixj.msg.outgoing" level="DEBUG"/>
```

---

## Troubleshooting

### Connection Refused

```
Failed to logon within timeout
```

- Verify the acceptor is running on the specified host:port
- Check firewall settings
- Verify CompIDs match between initiator and acceptor

### Session Rejected

```
Logon rejected
```

- Verify SenderCompID/TargetCompID are correct
- Check if sequence numbers need to be reset (use `--reset-on-logon`)
- Ensure FIX version (BeginString) matches

### No Execution Report Received

```
Should receive an execution report - Value was null
```

- Verify the acceptor supports the message type being sent
- Check if `--no-auto-ack` is enabled on the acceptor
- Increase timeout with `--timeout`

### Sequence Number Mismatch

If you see resend requests or sequence gaps:
- Use `--reset-on-logon` to reset sequences on each connection
- Delete the `./quickfix-store` directory to clear persisted sequences

---

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success (all tests passed) |
| 1 | Failure (one or more tests failed, or connection error) |

---

## Dependencies

The reference tester uses:
- **QuickFIX/J 2.3.1** - Industry-standard FIX engine implementation
- **PicoCLI** - Command-line interface framework
- **SLF4J + Logback** - Logging framework
