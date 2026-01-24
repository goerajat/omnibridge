# FIX Admin API & UI Architecture

## Overview

The admin system is a **Spring Boot REST API** (port 8080) with a **React frontend** (port 3000) that provides real-time monitoring and management of FIX sessions.

---

## Backend (fix-admin-api)

### REST API Endpoints

**Base Path:** `/api/sessions`

| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/` | List all sessions (with optional filters) |
| GET | `/{id}` | Get session details |
| POST | `/{id}/connect` | Connect (initiator only) |
| POST | `/{id}/disconnect` | Disconnect TCP |
| POST | `/{id}/logout` | Send FIX logout |
| POST | `/{id}/reset-sequence` | Reset both seq nums to 1 |
| POST | `/{id}/set-outgoing-seq` | Set outgoing seq num |
| POST | `/{id}/set-incoming-seq` | Set expected incoming seq num |
| POST | `/{id}/send-test-request` | Send FIX TestRequest |
| POST | `/{id}/trigger-eod` | Manual End-of-Day |

### WebSocket Real-Time Updates

**Endpoint:** `ws://localhost:8080/ws/sessions`

Broadcasts events when session state changes:
- `STATE_CHANGE` - Session state transitions
- `CONNECTED` / `DISCONNECTED` - TCP connection events
- `LOGON` / `LOGOUT` - FIX session events
- `EOD` - End-of-Day events

### Key Files

```
fix-admin-api/src/main/java/com/omnibridge/admin/
├── FixAdminApplication.java      # Spring Boot entry point
├── config/
│   ├── EngineConfig.java         # Creates FIX engine & sample sessions
│   └── WebSocketConfig.java      # WebSocket endpoint registration
├── controller/
│   └── SessionController.java    # REST endpoints
├── service/
│   └── SessionService.java       # Business logic, engine bridge
├── websocket/
│   └── SessionWebSocketHandler.java  # Real-time event broadcasting
└── dto/
    ├── SessionDto.java           # Session data transfer object
    └── SessionActionRequest.java # Action request payload
```

### Configuration (`application.yml`)

```yaml
server:
  port: 8080
fix:
  engine:
    enabled: true
    persistence-path: ./fix-logs
springdoc:
  swagger-ui:
    path: /swagger-ui.html
```

---

## Frontend (fix-admin-ui)

### Technology Stack

- **React 18** + TypeScript
- **React Query** - Data fetching with 2-second polling
- **Tailwind CSS** - Styling
- **Vite** - Build tool (dev server on port 3000)

### Pages

| Route | Component | Purpose |
|-------|-----------|---------|
| `/` | Dashboard | Grid of all sessions with summary stats |
| `/session/:id` | SessionDetail | Manage individual session |

### Dashboard Features

- Summary cards (total, connected, logged-on counts)
- Session cards showing:
  - Status indicator (green/yellow/red)
  - Role badge (INITIATOR/ACCEPTOR)
  - Sequence numbers
  - Endpoint info

### Session Detail Features

- **Info Panel:** CompIDs, role, endpoint, heartbeat interval
- **Sequence Numbers:** View/modify outgoing and incoming
- **Actions:** Connect, Disconnect, Logout, Reset, Test Request, Trigger EOD

---

## Data Flow

```
┌─────────────────┐    WebSocket     ┌─────────────────┐
│   React UI      │◄────────────────►│  Spring Boot    │
│   (port 3000)   │                  │   (port 8080)   │
└────────┬────────┘                  └────────┬────────┘
         │ REST API                           │
         │ /api/sessions/*                    │
         └────────────────────────────────────┘
                                              │
                                              ▼
                                     ┌─────────────────┐
                                     │   FIX Engine    │
                                     │   (FixSession)  │
                                     └─────────────────┘
```

1. **User action** → React mutation → REST API call
2. **SessionController** → **SessionService** → **FixEngine**
3. **Engine state change** → WebSocket broadcast → UI update

---

## Running the Admin System

```bash
# Terminal 1: Start API server
cd fix-admin-api
mvn spring-boot:run

# Terminal 2: Start UI dev server
cd fix-admin-ui
npm install
npm run dev
```

- **API:** http://localhost:8080
- **Swagger:** http://localhost:8080/swagger-ui.html
- **UI:** http://localhost:3000
