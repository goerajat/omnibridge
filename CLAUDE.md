# Claude Code Project Notes

## Code Navigation

Use the index MCP (IDE integration) tools for code navigation and refactoring instead of raw text search when possible:

- **`ide_find_definition`** — go to where a symbol is defined
- **`ide_find_references`** — find all usages of a symbol before modifying or removing it
- **`ide_find_implementations`** — find concrete implementations of interfaces/abstract classes
- **`ide_find_class`** / **`ide_find_file`** — fast lookup by name (supports camelCase and substring matching)
- **`ide_search_text`** — word index search (faster than file scanning for exact matches)
- **`ide_call_hierarchy`** — trace callers/callees of a method
- **`ide_type_hierarchy`** — view inheritance chains
- **`ide_diagnostics`** — check for compilation errors and available quick fixes
- **`ide_refactor_rename`** — safe rename across the entire project
- **`ide_refactor_safe_delete`** — delete with usage check to avoid breaking references

Prefer these over Grep/Glob for navigating Java code (finding definitions, tracing usage, understanding inheritance). Use `ide_sync_files` after creating or modifying files outside the IDE to keep the index current.

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

Available tests: LogonLogoutTest, SequenceNumberTest, TestRequestTest, HeartbeatTest, ResendRequestTest, DuplicateLogonTest, ResetSeqNumOnLogonTest, MultipleReconnectTest, SendInWrongStateTest, GapDetectionTest, DuplicateMessageTest, LogoutAcknowledgmentTest, RejectNotificationTest

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

## OmniView - Protocol Engine Monitor

OmniView is a web-based monitoring application for FIX/OUCH protocol engine applications.

### Building OmniView

```bash
# Build with Maven (includes frontend build and distribution package)
cd omniview
mvn package

# Build with pre-built frontend (faster)
cd omniview
npm run build
mvn package -Pskip-frontend
```

Build outputs:
- `omniview/target/omniview-1.0.0-SNAPSHOT.jar` - Executable JAR
- `omniview/target/omniview-1.0.0-SNAPSHOT-dist.tar.gz` - Distribution package (Linux)
- `omniview/target/omniview-1.0.0-SNAPSHOT-dist.zip` - Distribution package (Windows)

### Distribution Package

The distribution package contains:
```
omniview-{version}/
├── bin/
│   ├── omniview.sh      # Linux/Mac management script
│   └── omniview.bat     # Windows management script
├── lib/
│   └── omniview.jar     # Executable JAR
├── conf/
│   └── omniview.conf    # Configuration file
└── logs/                # Log directory (created at runtime)
```

### Running OmniView (from distribution)

```bash
# Linux/Mac
bin/omniview.sh start [port]    # Start (default port: 3000)
bin/omniview.sh stop            # Stop
bin/omniview.sh status          # Check status
bin/omniview.sh restart [port]  # Restart

# Windows
bin\omniview.bat start [port]
bin\omniview.bat stop
bin\omniview.bat status
bin\omniview.bat restart [port]
```

### Deploying to Remote Server

Deploy OmniView to a remote Linux server:

```bash
# Windows
deploy-omniview.bat -i <pem-file> -u <username> -h <hostname> [-p <port>] [-d <deploy-dir>]

# Linux/Mac
./deploy-omniview.sh -i <pem-file> -u <username> -h <hostname> [-p <port>] [-d <deploy-dir>]
```

Options:
- `-i, --identity` - Path to PEM file for SSH authentication (required)
- `-u, --user` - SSH username (required)
- `-h, --host` - Remote hostname or IP (required)
- `-p, --port` - OmniView server port (default: 3000)
- `-d, --deploy-dir` - Deployment directory (default: /opt/omniview)
- `-s, --ssh-port` - SSH port (default: 22)

Example:
```bash
./deploy-omniview.sh -i ~/.ssh/mykey.pem -u ubuntu -h 192.168.1.100 -p 8080
```

### Development Mode

```bash
cd omniview
npm install
npm run dev    # Starts dev server on http://localhost:3000
```

### Features

- Dashboard with app cards showing session health
- Real-time session monitoring via WebSocket
- Enable/disable session actions
- Protocol filtering (FIX/OUCH)
- Multi-app support with automatic reconnection

## Sample Applications Deployment

Deploy FIX and OUCH sample acceptors to a remote Linux server.

### Building Sample Applications

```bash
mvn package -DskipTests   # Build all modules including sample apps
```

Distribution packages:
- `apps/fix-samples/target/fix-samples-*-dist.tar.gz`
- `apps/ouch-samples/target/ouch-samples-*-dist.tar.gz`

### Deploying to Remote Server

```bash
# Windows
deploy-samples.bat -i <pem-file> -u <username> -h <hostname> [options]

# Linux/Mac
./deploy-samples.sh -i <pem-file> -u <username> -h <hostname> [options]
```

Options:
- `-i, --identity` - Path to PEM file for SSH authentication (required)
- `-u, --user` - SSH username (required)
- `-h, --host` - Remote hostname or IP (required)
- `-d, --deploy-dir` - Deployment directory (default: /opt/samples)
- `--fix-only` - Deploy only FIX acceptor
- `--ouch-only` - Deploy only OUCH acceptor

Example:
```bash
./deploy-samples.sh -i ~/.ssh/mykey.pem -u ubuntu -h 192.168.1.100
```

### Port Assignments

| Application    | Protocol Port | Admin Port |
|----------------|---------------|------------|
| FIX Acceptor   | 9876          | 8081       |
| OUCH Acceptor  | 9200          | 8082       |

### Remote Management

After deployment, manage acceptors via the samples.sh script:

```bash
ssh user@host '/opt/samples/samples.sh status'      # Check status
ssh user@host '/opt/samples/samples.sh restart all' # Restart both
ssh user@host '/opt/samples/samples.sh stop fix'    # Stop FIX only
ssh user@host '/opt/samples/samples.sh start ouch'  # Start OUCH only
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
