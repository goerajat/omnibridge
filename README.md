# OmniBridge

Ultra-low-latency, multi-protocol connectivity engine for financial markets.

[![CI](https://github.com/goerajat/omnibridge/actions/workflows/ci.yml/badge.svg)](https://github.com/goerajat/omnibridge/actions/workflows/ci.yml)

## Overview

OmniBridge is a high-performance protocol engine built in Java 17 for electronic trading connectivity. It provides a unified architecture for multiple exchange protocols with sub-microsecond message processing, lock-free I/O, shared-memory persistence, and real-time monitoring — designed from the ground up for latency-sensitive environments.

## Supported Protocols

| Protocol | Versions | Transport | Use Case |
|----------|----------|-----------|----------|
| **FIX** | 4.2, 4.4, 5.0 | TCP | Equities, derivatives, FX |
| **OUCH** | 4.2, 5.0 | TCP (SoupBinTCP) | NASDAQ-style order entry |
| **iLink 3** | CME | TCP (SBE) | CME Globex derivatives |
| **Optiq** | Euronext OEG | TCP (SBE) | Euronext cash & derivatives |
| **Pillar** | NYSE | TCP (binary) | NYSE equities |

## Low-Latency Design

OmniBridge applies several techniques to minimize latency across the message path:

- **Lock-free ring buffers** — Agrona `ManyToOneRingBuffer` for zero-contention inter-thread messaging
- **Zero-copy message encoding** — Flyweight pattern over `DirectByteBuffer`; no intermediate object allocation
- **Single-threaded event loop** — `NetworkEventLoop` with non-blocking NIO; one thread handles all I/O
- **CPU affinity** — Pin event-loop threads to isolated cores via JNA
- **Busy-spin mode** — Optional busy-wait polling for sub-microsecond wake-up latency
- **Pre-allocated buffers** — All read/write buffers allocated at startup; no allocations on the hot path
- **GC tuning** — G1GC with 10ms max pause target; `-XX:+AlwaysPreTouch` for predictable memory access

## Persistence

Three persistence modes for message logging and replay:

| Mode | Implementation | Description |
|------|----------------|-------------|
| `chronicle` | Chronicle Queue | Memory-mapped append-only log with microsecond writes (default) |
| `memory-mapped` | Custom `MemoryMappedLogStore` | Lightweight memory-mapped files without Chronicle dependency |
| `none` | — | No persistence; lowest latency |

### Architecture

The persistence layer is protocol-agnostic. A single `LogStore` interface is backed by either Chronicle Queue or custom memory-mapped files, selected via `store-type` config. Each protocol session (FIX, OUCH, etc.) writes to its own **stream** — a separate Chronicle Queue directory or memory-mapped file.

**Binary entry format** (written via Chronicle Queue's raw `Bytes` API — no named Wire fields):

```
timestamp       8 bytes (long)     Epoch milliseconds
direction       1 byte             0 = INBOUND, 1 = OUTBOUND
sequenceNumber  4 bytes (int)      Protocol-level sequence number
metadataLen     2 bytes (short)    Length of metadata
metadata        N bytes            Protocol-specific metadata
rawMessageLen   4 bytes (int)      Length of raw message
rawMessage      N bytes            Raw protocol message bytes
```

### Streams

Streams are created **lazily on first write** — no pre-configuration needed. Stream names come from session IDs:

- **FIX**: `senderCompId + "->" + targetCompId` (e.g., `EXCHANGE->CLIENT`)
- **OUCH**: the session ID string

Stream names are sanitized for the file system (`->` becomes `_to_`, invalid chars replaced with `_`).

### File System Layout

```
fix-logs/acceptor/                      # basePath from config
  EXCHANGE_to_CLIENT/                   # sanitized stream name = directory
    metadata.cq4t                       # Chronicle Queue metadata
    20260219F.cq4                       # data file (daily rolling)
    20260220F.cq4
  CLIENT_to_EXCHANGE/
    metadata.cq4t
    20260219F.cq4
```

On startup, existing streams are auto-discovered by scanning for subdirectories containing `.cq4` files.

### Write Path

FIX sessions enforce **single-writer semantics** per stream:
- **Incoming messages** — logged on the session's receive thread
- **Outgoing messages** — logged via `TcpChannel.OutgoingMessageListener` on the event loop thread (fires during ring buffer drain)

Writes use a flyweight `LogEntry` with pre-allocated byte buffers to avoid allocation on the hot path.

### Readers

- **Single-stream reader** (`ChronicleLogReader`) — backed by a Chronicle `ExcerptTailer` with `poll()`, `drain()`, and `setPosition()` support
- **All-streams reader** (`ChronicleAllStreamsLogReader`) — merge-sorts entries from all streams by timestamp using per-stream peek buffers

### Decoder Integration

The `Decoder` interface provides protocol-specific message interpretation:

| Decoder | Protocol | Capabilities |
|---------|----------|-------------|
| `FIXDecoder` | FIX | Extracts tag 35 (MsgType), tag 34 (MsgSeqNum), formats with `\|` separators |
| `OuchDecoder` | OUCH | Decodes binary messages via flyweight wrappers, formats as JSON |

Decoders are attached per-stream via `logStore.setDecoder(streamName, decoder)` and stored in-memory.

### CLI Log Viewer

The `LogViewer` CLI tool (`persistence-*-cli.jar`) provides offline log inspection:

```bash
alias fixlog="java -jar persistence/target/persistence-*-cli.jar"
```

| Command | Description |
|---------|-------------|
| `fixlog list <path>` | List streams with entry counts and last sequence numbers |
| `fixlog show <path>` | Show messages with filters (direction, type, time/seq range) |
| `fixlog search <path>` | Regex search across raw messages and metadata |
| `fixlog stats <path>` | Statistics with optional `--by-type` breakdown |
| `fixlog tail <path>` | Real-time follow (like `tail -f`) |
| `fixlog export <path>` | Export to text, JSON, CSV, or raw format |
| `fixlog msgtypes` | List all known FIX message type codes |

Common filter options: `--stream`, `--direction in/out`, `--type D/8/A`, `--from-time`/`--to-time`, `--from-seq`/`--to-seq`, `--skip-admin`, `--decode-types`, `-n count`, `-f format`.

The CLI auto-detects whether a directory uses Chronicle Queue or memory-mapped files. See `persistence/CLI.md` for full documentation.

## OmniView — Real-Time Monitoring

OmniView is a web-based dashboard for monitoring OmniBridge engine instances.

- **React SPA** served from an embedded Jetty server
- **WebSocket** push for real-time session state updates
- **Dashboard** with application cards showing session health at a glance
- **Session actions** — enable/disable sessions directly from the UI
- **Protocol filtering** — view FIX, OUCH, or all sessions
- **Multi-app support** — monitor multiple engine instances with automatic reconnection

## Project Structure

```
omnibridge/
├── config/                          # HOCON configuration & component lifecycle
├── network/                         # Non-blocking TCP I/O, CPU affinity, event loop
├── persistence/                     # Chronicle Queue & memory-mapped log stores
├── admin/                           # REST + WebSocket admin API (Javalin)
├── protocols/
│   ├── fix/
│   │   ├── dictionary/              # FIX data dictionary parser
│   │   ├── message/                 # FIX message codec (flyweight)
│   │   ├── engine/                  # FIX session & engine
│   │   ├── session-tester/          # Session-level test CLI
│   │   └── reference-tester/        # QuickFIX/J interoperability tests
│   ├── ouch/
│   │   ├── message/                 # OUCH binary message codec (v4.2 / v5.0)
│   │   └── engine/                  # OUCH session & engine
│   └── sbe/
│       ├── message/                 # SBE base message classes & pool
│       ├── engine/                  # SBE base engine & session
│       ├── ilink3/                  # CME iLink 3 (message + engine)
│       ├── optiq/                   # Euronext Optiq (message + engine)
│       └── pillar/                  # NYSE Pillar (message + engine)
├── apps/
│   ├── common/                      # Shared application base & lifecycle
│   ├── fix-samples/                 # FIX initiator & acceptor samples
│   ├── ouch-samples/                # OUCH initiator & acceptor samples
│   ├── ilink3-samples/              # iLink 3 samples
│   ├── optiq-samples/               # Optiq samples
│   ├── pillar-samples/              # Pillar samples
│   ├── exchange-simulator-core/     # Order book & fill engine library
│   └── exchange-simulator/          # Multi-protocol exchange simulator
├── omniview/                        # React monitoring dashboard
└── build-tools/                     # Integration test runner
```

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.8+
- Node.js 18+ (for OmniView only)

### Build

```bash
mvn install -DskipTests
```

### Run the FIX Sample Acceptor

```bash
java -jar apps/fix-samples/target/fix-samples-*-all.jar
```

The acceptor listens on port **9876** with default CompIDs `EXCHANGE` / `CLIENT`.

### Run Latency Tests

```bash
# FIX
./run-latency-test.sh 10000 1000 100

# OUCH 4.2
./run-ouch-latency-test.sh 10000 1000 100

# OUCH 5.0
./run-ouch-latency-test-v50.sh 10000 1000 100
```

Parameters: `[warmup-orders] [test-orders] [rate]`. Results include min, max, avg, p50, p90, p95, p99, and p99.9 latencies.

## Configuration

OmniBridge uses [HOCON](https://github.com/lightbend/config) for configuration. Example:

```hocon
# Network event loop
network {
    name = "main-event-loop"
    cpu-affinity = 2                   # Pin to core 2 (-1 to disable)
    read-buffer-size = 65536
    write-buffer-size = 65536
    busy-spin-mode = false             # true for lowest latency
}

# Persistence
persistence {
    enabled = true
    store-type = "chronicle"           # chronicle | memory-mapped | none
    base-path = "./data/fix-logs"
    max-file-size = 256MB
    sync-on-write = false
}

# FIX sessions
fix-engine {
    sessions = [
        {
            session-id = "PROD-1"
            port = 9876
            sender-comp-id = "MYAPP"
            target-comp-id = "EXCHANGE"
            initiator = true
            host = "exchange.example.com"
            begin-string = "FIX.4.4"
            heartbeat-interval = 30
        }
    ]
}

# Admin API
admin {
    enabled = true
    port = 8080
    context-path = "/api"
    websocket {
        enabled = true
        path = "/ws"
    }
}
```

## Testing

### Reference Tests (QuickFIX/J Interoperability)

```bash
./run-reference-test.sh all
./run-reference-test.sh HeartbeatTest,NewOrderTest
```

Available: `HeartbeatTest`, `SequenceNumberTest`, `NewOrderTest`, `MarketOrderTest`, `OrderCancelTest`, `OrderModifyTest`, `MultipleOrdersTest`, `LogonLogoutTest`

### Session Tests

```bash
./run-session-test.sh all text
./run-session-test.sh HeartbeatTest json
```

Available: `LogonLogoutTest`, `SequenceNumberTest`, `TestRequestTest`, `HeartbeatTest`, `ResendRequestTest`

Report formats: `text` (default), `json`, `html`

### Unit Tests

```bash
mvn test
```

## Exchange Simulator

A multi-protocol exchange simulator with a configurable fill engine for development and testing.

**Supported protocols:** FIX, OUCH 4.2, OUCH 5.0, iLink 3, Optiq, Pillar

**Default ports:**

| Protocol | Port |
|----------|------|
| FIX | 9876 |
| OUCH 4.2 | 9200 |
| OUCH 5.0 | 9201 |
| iLink 3 | 9300 |
| Optiq | 9400 |
| Pillar | 9500 |
| Admin API | 8080 |

**Fill rules** are symbol-driven and configurable — set fill probability, partial-fill ratios, and match patterns like `TEST*`, `HALT*`, or `*` (default).

## Admin API

### REST Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/sessions` | List all sessions |
| GET | `/api/sessions/stats` | Session statistics |
| GET | `/api/sessions/{id}` | Get session by ID |
| POST | `/api/sessions/{id}/enable` | Enable a session |
| POST | `/api/sessions/{id}/disable` | Disable a session |
| POST | `/api/sessions/enable-all` | Enable all sessions |
| POST | `/api/sessions/disable-all` | Disable all sessions |
| GET | `/api/sessions/protocol/{type}` | Filter sessions by protocol |
| GET | `/api/sessions/connected` | List connected sessions |
| GET | `/api/sessions/logged-on` | List logged-on sessions |
| PUT | `/api/sessions/{id}/sequence` | Set sequence numbers |

### WebSocket

Connect to `/ws` for real-time session state change events (JSON).

## Deployment

### Distribution Packages

Build produces distribution archives for sample apps, the exchange simulator, and OmniView:

```bash
mvn package -DskipTests
```

Outputs:
- `apps/fix-samples/target/fix-samples-*-dist.tar.gz`
- `apps/ouch-samples/target/ouch-samples-*-dist.tar.gz`
- `apps/exchange-simulator/target/exchange-simulator-*-dist.tar.gz`
- `omniview/target/omniview-*-dist.tar.gz`

### Remote Deployment

```bash
# Sample acceptors
./deploy-samples.sh -i ~/.ssh/key.pem -u ubuntu -h 192.168.1.100

# Exchange simulator
./deploy-exchange-simulator.sh -i ~/.ssh/key.pem -u ubuntu -h 192.168.1.100

# OmniView
./deploy-omniview.sh -i ~/.ssh/key.pem -u ubuntu -h 192.168.1.100 -p 3000
```

### OmniView Management

```bash
bin/omniview.sh start [port]    # Default port: 3000
bin/omniview.sh stop
bin/omniview.sh status
bin/omniview.sh restart [port]
```

## Tech Stack

| Component | Library | Version |
|-----------|---------|---------|
| Ring buffers | Agrona | 1.20.0 |
| Persistence | Chronicle Queue | 5.24ea23 |
| Configuration | Lightbend Config (HOCON) | 1.4.3 |
| Admin API | Javalin | 6.1.3 |
| Monitoring UI | React + Vite | — |
| CPU affinity | JNA | 5.14.0 |
| CLI | picocli | 4.7.5 |
| Serialization | Jackson | 2.16.1 |
| Logging | SLF4J + Logback | 2.0.9 / 1.4.14 |
| Reference testing | QuickFIX/J | 2.3.1 |
| Build | Maven + Java 17 | — |

## License

Proprietary — All rights reserved.

## AI / Machine Learning Usage Prohibited

No part of this repository—including source code, documentation, comments, or commit history—may be used to train, fine-tune, or improve machine learning or artificial intelligence models (including large language models), whether for commercial or non-commercial purposes, without prior written consent from the repository owner.
The contents of this repository are expressly excluded from use in training, fine-tuning, or benchmarking artificial intelligence or machine learning models (including but not limited to large language models).
Any such use is prohibited without explicit, prior, written permission from the repository owner. This restriction applies to all forms of data extraction, scraping, aggregation, or automated analysis for AI-related purposes.
