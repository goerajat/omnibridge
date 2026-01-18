# Connectivity - Ultra Low Latency FIX Engine

High-performance FIX protocol engine with non-blocking I/O and shared memory persistence.

## Project Structure

```
connectivity/
├── fix-network-io/      # Non-blocking TCP networking
├── fix-persistence/     # Shared memory message logging
├── fix-message/         # FIX message parsing/serialization
├── fix-engine/          # Core FIX protocol engine
├── fix-sample-apps/     # Sample initiator and acceptor
├── fix-session-tester/  # Session testing CLI tool
├── fix-admin-api/       # REST API for administration
└── fix-admin-ui/        # React web UI for administration
```

## Prerequisites

- Java 17 or higher
- Maven 3.8+
- Node.js 18+ and npm (for the React UI)

## Build Instructions

### Build All Java Modules

```bash
cd connectivity
mvn clean install
```

This builds all Maven modules including:
- fix-network-io
- fix-persistence
- fix-message
- fix-engine
- fix-sample-apps
- fix-session-tester
- fix-admin-api

### Build React UI

```bash
cd fix-admin-ui
npm install
npm run build
```

## Running the Applications

### 1. Sample Acceptor (Exchange Simulator)

Start the sample acceptor to simulate an exchange:

```bash
cd fix-sample-apps
java -jar target/fix-sample-apps-1.0.0-SNAPSHOT-all.jar --port 9880
```

Options:
- `--port` - Port to listen on (default: 9880)
- `--sender` - SenderCompID (default: EXCHANGE)
- `--target` - TargetCompID (default: CLIENT)
- `--fill-rate` - Order fill rate 0.0-1.0 (default: 1.0)

### 2. Sample Initiator (Trading Client)

Start the sample initiator to connect to an acceptor:

```bash
cd fix-sample-apps
java -cp target/fix-sample-apps-1.0.0-SNAPSHOT-all.jar \
  com.fixengine.samples.initiator.SampleInitiator \
  --host localhost --port 9880
```

Options:
- `--host` - Target host (default: localhost)
- `--port` - Target port (default: 9880)
- `--sender` - SenderCompID (default: CLIENT)
- `--target` - TargetCompID (default: EXCHANGE)
- `--mode` - Mode: interactive, auto, warmup, latency

### 3. REST Admin API

Start the Spring Boot REST API:

```bash
cd fix-admin-api
mvn spring-boot:run
```

Or run the packaged JAR:

```bash
java -jar target/fix-admin-api-1.0.0-SNAPSHOT.jar
```

The API will be available at:
- REST API: http://localhost:8080/api/sessions
- Swagger UI: http://localhost:8080/swagger-ui.html
- WebSocket: ws://localhost:8080/ws/sessions

### 4. React Admin UI

Development mode:

```bash
cd fix-admin-ui
npm install
npm run dev
```

The UI will be available at http://localhost:3000

Production build:

```bash
npm run build
npm run preview
```

### 5. Session Tester CLI

Run tests against a FIX acceptor:

```bash
cd fix-session-tester
java -jar target/fix-session-tester-1.0.0-SNAPSHOT-all.jar \
  --host localhost \
  --port 9880 \
  --tests all \
  --report-format text
```

Options:
- `--host, -h` - Target host (default: localhost)
- `--port, -p` - Target port (default: 9880)
- `--sender` - SenderCompID (default: TESTER)
- `--target` - TargetCompID (default: ACCEPTOR)
- `--tests, -t` - Comma-separated test names or "all"
- `--report-format, -f` - Output format: text, json, html
- `--output, -o` - Output file (stdout if not specified)
- `--timeout` - Default timeout in seconds (default: 30)
- `--list-tests, -l` - List available tests and exit

Available tests:
- `LogonLogoutTest` - Tests logout, disconnect, reconnect, logon sequence
- `SequenceNumberTest` - Tests sequence number get/set/reset operations
- `TestRequestTest` - Tests TestRequest/Heartbeat exchange
- `HeartbeatTest` - Tests automatic heartbeat timing
- `ResendRequestTest` - Tests resend request handling

## REST API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/sessions` | List all sessions |
| GET | `/api/sessions/{id}` | Get session details |
| POST | `/api/sessions/{id}/connect` | Connect session |
| POST | `/api/sessions/{id}/disconnect` | Disconnect session |
| POST | `/api/sessions/{id}/logout` | Logout session |
| POST | `/api/sessions/{id}/reset-sequence` | Reset sequence numbers to 1 |
| POST | `/api/sessions/{id}/set-outgoing-seq` | Set outgoing sequence number |
| POST | `/api/sessions/{id}/set-incoming-seq` | Set incoming sequence number |
| POST | `/api/sessions/{id}/send-test-request` | Send test request |
| POST | `/api/sessions/{id}/trigger-eod` | Trigger End of Day reset |
| GET | `/api/sessions/health` | Health check |

Query parameters for GET `/api/sessions`:
- `state` - Filter by session state
- `connected` - Filter by TCP connection status
- `loggedOn` - Filter by FIX logon status

## Configuration

### Session Configuration

```java
SessionConfig config = SessionConfig.builder()
    .sessionName("MySession")
    .senderCompId("CLIENT")
    .targetCompId("EXCHANGE")
    .host("localhost")
    .port(9880)
    .initiator()  // or .acceptor()
    .heartbeatInterval(30)
    .resetOnLogon(true)
    .resetOnLogout(false)
    .resetOnDisconnect(false)
    .eodTime(LocalTime.of(17, 0))  // EOD at 5pm
    .resetOnEod(true)
    .persistencePath("./fix-logs")
    .build();
```

### Engine Configuration

```java
EngineConfig engineConfig = EngineConfig.builder()
    .persistencePath("./fix-logs")
    .maxLogFileSize(1024 * 1024 * 1024)  // 1GB
    .cpuAffinity(0)  // Pin to CPU 0, or -1 for no affinity
    .addSession(sessionConfig)
    .build();
```

### Admin API Configuration (application.yml)

```yaml
server:
  port: 8080

fix:
  engine:
    enabled: true
    persistence-path: ./fix-logs
```

## End of Day (EOD) Functionality

Sessions can be configured to automatically reset sequence numbers at a specific time:

```java
SessionConfig.builder()
    .eodTime(LocalTime.of(17, 0))  // 5:00 PM
    .resetOnEod(true)
    .timeZone("America/New_York")
    .build();
```

EOD events are:
- Logged to persistence with metadata
- Broadcast via WebSocket to connected clients
- Triggerable manually via API: `POST /api/sessions/{id}/trigger-eod`

## Development

### Running Tests

```bash
mvn test
```

### Code Style

The project follows standard Java conventions:
- Builder pattern for configuration objects
- CopyOnWriteArrayList for thread-safe listeners
- AtomicInteger/AtomicReference for thread-safe state
- SLF4J for logging

## License

Proprietary - All rights reserved.

## AI / Machine Learning Usage Prohibited

No part of this repository—including source code, documentation, comments, or commit history—may be used to train, fine-tune, or improve machine learning or artificial intelligence models (including large language models), whether for commercial or non-commercial purposes, without prior written consent from the repository owner.
The contents of this repository are expressly excluded from use in training, fine-tuning, or benchmarking artificial intelligence or machine learning models (including but not limited to large language models).
Any such use is prohibited without explicit, prior, written permission from the repository owner. This restriction applies to all forms of data extraction, scraping, aggregation, or automated analysis for AI-related purposes.

