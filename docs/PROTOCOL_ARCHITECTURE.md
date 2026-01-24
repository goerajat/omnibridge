# Exchange Protocol Implementation Architecture

This document describes the architecture, design patterns, and code structure used to implement FIX and OUCH protocols in this ultra-low latency trading engine. Use this as a reference guide when implementing new exchange protocols.

## Table of Contents

1. [Overview](#1-overview)
2. [Module Structure](#2-module-structure)
3. [Message Encoding and Decoding](#3-message-encoding-and-decoding)
4. [Network Layer and Ring Buffers](#4-network-layer-and-ring-buffers)
5. [Session Management](#5-session-management)
   - [5.5 Centralized Session Management Service](#55-centralized-session-management-service)
   - [5.6 Session Schedule Association](#56-session-schedule-association)
6. [Configuration Framework](#6-configuration-framework)
   - [6.1 Component Lifecycle](#61-component-lifecycle)
   - [6.2 LifeCycleComponent](#62-lifecyclecomponent)
   - [6.3 ComponentFactory Interface](#63-componentfactory-interface)
   - [6.4 Creating a ComponentFactory](#64-creating-a-componentfactory)
   - [6.5 Factory Naming Convention](#65-factory-naming-convention)
   - [6.6 ComponentProvider and Registry](#66-componentprovider-and-registry)
   - [6.7 Config-Driven Component Loading](#67-config-driven-component-loading)
   - [6.8 Application Lifecycle Hooks](#68-application-lifecycle-hooks)
   - [6.9 Configuration Loading](#69-configuration-loading)
   - [6.10 Complete Configuration Example](#610-complete-configuration-example)
   - [6.11 Creating a New Component](#611-creating-a-new-component)
7. [Admin API](#7-admin-api)
   - [7.1 REST API](#71-rest-api)
   - [7.2 WebSocket API](#72-websocket-api)
   - [7.3 Configuration](#73-configuration)
8. [Testing Infrastructure](#8-testing-infrastructure)
9. [Multi-Version Protocol Support](#9-multi-version-protocol-support)
10. [Creating a New Protocol](#10-creating-a-new-protocol)
11. [Performance Considerations](#11-performance-considerations)

---

## 1. Overview

### Design Goals

- **Ultra-low latency**: Sub-millisecond message processing
- **Zero-copy operations**: Minimize memory allocation and copying
- **Lock-free concurrency**: Use ring buffers for thread-safe message passing
- **Protocol flexibility**: Support multiple protocols and versions
- **High availability**: Component lifecycle with ACTIVE/STANDBY states
- **Self-testing**: Comprehensive test frameworks for protocol compliance

### Key Technologies

| Technology | Purpose |
|------------|---------|
| Agrona | Lock-free data structures (ring buffers, direct buffers) |
| Chronicle Queue | Memory-mapped persistence |
| Typesafe Config | HOCON configuration loading |
| JNA | CPU affinity for thread pinning |
| NIO Selector | Non-blocking I/O |

---

## 2. Module Structure

### 2.1 Directory Layout

```
connectivity/
├── pom.xml                          # Root POM with dependency management
├── config/                          # Configuration framework
│   └── src/main/java/com/omnibridge/config/
│       ├── ConfigLoader.java        # HOCON configuration loading
│       ├── Component.java           # Lifecycle interface
│       ├── ComponentProvider.java   # Dependency injection
│       └── schedule/                # Session scheduling
├── network/                         # Network I/O layer
│   └── src/main/java/com/omnibridge/network/
│       ├── NetworkEventLoop.java    # NIO event loop
│       ├── TcpChannel.java          # Per-connection channel with ring buffer
│       ├── TcpAcceptor.java         # Server socket acceptor
│       └── NetworkHandler.java      # Callback interface
├── persistence/                     # Message logging
│   └── src/main/java/com/omnibridge/persistence/
│       ├── LogStore.java            # Persistence interface
│       ├── MemoryMappedLogStore.java # Chronicle Queue implementation
│       └── LogEntry.java            # Message wrapper
├── fix/                             # FIX protocol implementation
│   ├── pom.xml                      # FIX parent POM
│   ├── message/                     # Message encoding/decoding
│   ├── engine/                      # Session and engine
│   ├── session-tester/              # Self-testing framework
│   └── reference-tester/            # QuickFIX/J interop testing
├── ouch/                            # OUCH protocol implementation
│   ├── pom.xml                      # OUCH parent POM
│   ├── message/                     # Message encoding/decoding
│   │   └── src/main/java/.../
│   │       ├── OuchMessage.java     # Base message class
│   │       ├── OuchVersion.java     # Version enum (V42, V50)
│   │       ├── v42/                 # OUCH 4.2 messages
│   │       └── v50/                 # OUCH 5.0 messages with appendages
│   └── engine/                      # Session and engine
├── apps/                            # Sample applications
│   ├── common/                      # Shared utilities
│   ├── fix-samples/                 # FIX acceptor/initiator
│   └── ouch-samples/                # OUCH acceptor/initiator
└── run-*.bat/.sh                    # Test scripts
```

### 2.2 Module Dependencies

```
                    ┌─────────┐
                    │ config  │
                    └────┬────┘
                         │
              ┌──────────┼──────────┐
              │          │          │
         ┌────▼────┐ ┌───▼───┐ ┌────▼─────┐
         │ network │ │persist│ │ schedule │
         └────┬────┘ └───┬───┘ └────┬─────┘
              │          │          │
              └──────────┼──────────┘
                         │
              ┌──────────┴──────────┐
              │                     │
        ┌─────▼─────┐        ┌──────▼─────┐
        │fix/message│        │ouch/message│
        └─────┬─────┘        └──────┬─────┘
              │                     │
        ┌─────▼─────┐        ┌──────▼─────┐
        │fix/engine │        │ouch/engine │
        └─────┬─────┘        └──────┬─────┘
              │                     │
        ┌─────▼─────┐        ┌──────▼──────┐
        │fix-samples│        │ouch-samples │
        └───────────┘        └─────────────┘
```

### 2.3 Maven Module Configuration

Each protocol follows this pattern:

```xml
<!-- Protocol parent POM (e.g., fix/pom.xml) -->
<project>
    <parent>
        <groupId>com.omnibridge</groupId>
        <artifactId>connectivity</artifactId>
    </parent>
    <artifactId>fix</artifactId>
    <packaging>pom</packaging>
    <modules>
        <module>message</module>
        <module>engine</module>
        <module>session-tester</module>
        <module>reference-tester</module>
    </modules>
</project>
```

---

## 3. Message Encoding and Decoding

### 3.1 Zero-Copy Flyweight Pattern

All message classes use the flyweight pattern to wrap raw byte buffers without copying:

```java
public abstract class OuchMessage {
    protected DirectBuffer buffer;
    protected int offset;
    protected int length;

    // Wrap existing buffer for reading (no copy)
    public OuchMessage wrapForReading(DirectBuffer buffer, int offset, int length) {
        this.buffer = buffer;
        this.offset = offset;
        this.length = length;
        return this;
    }

    // Wrap buffer region for writing
    public OuchMessage wrapForWriting(MutableDirectBuffer buffer, int offset,
                                       int maxLength, int claimIndex) {
        this.buffer = buffer;
        this.offset = offset;
        this.length = maxLength;
        this.claimIndex = claimIndex;
        return this;
    }
}
```

### 3.2 Field Access Methods

For fixed-width binary protocols, define field offsets as constants:

```java
public class V42EnterOrderMessage extends OuchMessage {
    // Field offsets
    public static final int MSG_TYPE_OFFSET = 0;
    public static final int ORDER_TOKEN_OFFSET = 1;
    public static final int ORDER_TOKEN_LENGTH = 14;
    public static final int SIDE_OFFSET = 15;
    public static final int SHARES_OFFSET = 16;
    public static final int SYMBOL_OFFSET = 20;
    public static final int SYMBOL_LENGTH = 8;
    public static final int PRICE_OFFSET = 28;

    public static final int MESSAGE_LENGTH = 48;

    // Getters read directly from buffer
    public String getOrderToken() {
        return getAlpha(ORDER_TOKEN_OFFSET, ORDER_TOKEN_LENGTH);
    }

    public int getShares() {
        return buffer.getInt(offset + SHARES_OFFSET, ByteOrder.BIG_ENDIAN);
    }

    // Setters write directly to buffer
    public V42EnterOrderMessage setShares(int shares) {
        ((MutableDirectBuffer) buffer).putInt(
            offset + SHARES_OFFSET, shares, ByteOrder.BIG_ENDIAN);
        return this;
    }
}
```

### 3.3 Base Class Helper Methods

The base message class provides common encoding/decoding utilities:

```java
public abstract class OuchMessage {
    // Reading helpers
    protected int getInt(int fieldOffset) {
        return buffer.getInt(offset + fieldOffset, ByteOrder.BIG_ENDIAN);
    }

    protected long getLong(int fieldOffset) {
        return buffer.getLong(offset + fieldOffset, ByteOrder.BIG_ENDIAN);
    }

    protected char getChar(int fieldOffset) {
        return (char) buffer.getByte(offset + fieldOffset);
    }

    protected String getAlpha(int fieldOffset, int length) {
        byte[] bytes = new byte[length];
        buffer.getBytes(offset + fieldOffset, bytes);
        return new String(bytes, StandardCharsets.US_ASCII).trim();
    }

    protected long getPrice(int fieldOffset) {
        // Price in 1/10000 units
        return buffer.getInt(offset + fieldOffset, ByteOrder.BIG_ENDIAN);
    }

    // Writing helpers
    protected void putInt(int fieldOffset, int value) {
        ((MutableDirectBuffer) buffer).putInt(
            offset + fieldOffset, value, ByteOrder.BIG_ENDIAN);
    }

    protected void putAlpha(int fieldOffset, String value, int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) ' ');  // Pad with spaces
        byte[] valueBytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(valueBytes, 0, bytes, 0,
            Math.min(valueBytes.length, length));
        ((MutableDirectBuffer) buffer).putBytes(offset + fieldOffset, bytes);
    }

    protected void putChar(int fieldOffset, char value) {
        ((MutableDirectBuffer) buffer).putByte(offset + fieldOffset, (byte) value);
    }
}
```

### 3.4 Message Reader (Incoming)

Create a reader class that parses incoming messages:

```java
public class V42MessageReader {
    // Flyweight instances for zero-allocation parsing
    private final V42EnterOrderMessage enterOrder = new V42EnterOrderMessage();
    private final V42OrderAcceptedMessage orderAccepted = new V42OrderAcceptedMessage();
    // ... other message types

    public OuchMessage read(DirectBuffer buffer, int offset, int length) {
        byte typeCode = buffer.getByte(offset);
        OuchMessageType type = OuchMessageType.fromCode(typeCode);

        OuchMessage message = switch (type) {
            case ENTER_ORDER -> enterOrder;
            case ORDER_ACCEPTED -> orderAccepted;
            // ... other types
            default -> null;
        };

        if (message != null) {
            message.wrapForReading(buffer, offset, length);
        }
        return message;
    }

    public OuchMessageType peekType(DirectBuffer buffer, int offset) {
        return OuchMessageType.fromCode(buffer.getByte(offset));
    }

    public int getExpectedLength(OuchMessageType type) {
        return switch (type) {
            case ENTER_ORDER -> V42EnterOrderMessage.MESSAGE_LENGTH;
            case ORDER_ACCEPTED -> V42OrderAcceptedMessage.MESSAGE_LENGTH;
            // ...
        };
    }
}
```

### 3.5 Message Pool

Use object pooling to reduce GC pressure:

```java
public class V42MessagePool {
    // Thread-local for thread safety without locks
    private static final ThreadLocal<V42MessagePool> THREAD_LOCAL =
        ThreadLocal.withInitial(V42MessagePool::new);

    // Pre-allocated instances
    private final V42EnterOrderMessage enterOrder = new V42EnterOrderMessage();
    private final V42OrderAcceptedMessage orderAccepted = new V42OrderAcceptedMessage();

    public static V42MessagePool get() {
        return THREAD_LOCAL.get();
    }

    @SuppressWarnings("unchecked")
    public <T extends OuchMessage> T getMessage(Class<T> messageClass) {
        OuchMessage msg = getMessageInstance(messageClass);
        msg.reset();
        return (T) msg;
    }

    private OuchMessage getMessageInstance(Class<?> clazz) {
        if (clazz == V42EnterOrderMessage.class) return enterOrder;
        if (clazz == V42OrderAcceptedMessage.class) return orderAccepted;
        throw new IllegalArgumentException("Unknown message class: " + clazz);
    }
}
```

### 3.6 Message Factory

Create a factory that abstracts version differences:

```java
public interface OuchMessageFactory {
    OuchVersion getVersion();
    OuchMessage getMessage(OuchMessageType type);
    <T extends OuchMessage> T getMessage(Class<T> messageClass);
    OuchMessage readMessage(DirectBuffer buffer, int offset, int length);
    int getExpectedLength(OuchMessageType type);
    void release(OuchMessage message);
}

public class V42MessageFactory implements OuchMessageFactory {
    private final V42MessagePool pool = new V42MessagePool();
    private final V42MessageReader reader = new V42MessageReader();

    @Override
    public OuchVersion getVersion() {
        return OuchVersion.V42;
    }

    @Override
    public OuchMessage readMessage(DirectBuffer buffer, int offset, int length) {
        return reader.read(buffer, offset, length);
    }
}
```

---

## 4. Network Layer and Ring Buffers

### 4.1 TCP Channel Ring Buffer

Each TCP connection has a dedicated ring buffer for outgoing messages:

```java
public class TcpChannel {
    private final ManyToOneRingBuffer ringBuffer;
    private final AtomicBuffer ringBufferBacking;
    private static final int RING_BUFFER_CAPACITY = 1024 * 1024; // 1MB

    public TcpChannel(SocketChannel socket) {
        // Allocate ring buffer backing store
        this.ringBufferBacking = new UnsafeBuffer(
            ByteBuffer.allocateDirect(RING_BUFFER_CAPACITY +
                RingBufferDescriptor.TRAILER_LENGTH));
        this.ringBuffer = new ManyToOneRingBuffer(ringBufferBacking);
    }

    /**
     * Try to claim space in the ring buffer for a message.
     * @return claim index if successful, -1 if buffer full
     */
    public int tryClaim(int length) {
        return ringBuffer.tryClaim(MSG_TYPE_ID, length);
    }

    /**
     * Get the buffer to write the message into.
     */
    public MutableDirectBuffer buffer() {
        return ringBuffer.buffer();
    }

    /**
     * Commit the claimed message for sending.
     */
    public void commit(int index) {
        ringBuffer.commit(index);
        // Wake up selector to send
        wakeupSelector();
    }

    /**
     * Abort a claim without sending.
     */
    public void abort(int index) {
        ringBuffer.abort(index);
    }
}
```

### 4.2 Draining Ring Buffer to Socket

The event loop drains ring buffers to sockets:

```java
public class TcpChannel {
    /**
     * Drain pending messages from ring buffer to socket.
     * Called by event loop on OP_WRITE or after message commit.
     */
    public int drainRingBufferToSocket() {
        int bytesWritten = 0;

        // Read all available messages
        ringBuffer.read((msgTypeId, buffer, offset, length) -> {
            try {
                // Zero-copy write directly from ring buffer
                ByteBuffer view = getDirectByteBufferView(buffer, offset, length);
                int written = socketChannel.write(view);
                return written == length;
            } catch (IOException e) {
                return false;
            }
        });

        return bytesWritten;
    }

    // Create a view into the ring buffer without copying
    private ByteBuffer getDirectByteBufferView(AtomicBuffer buffer,
                                                int offset, int length) {
        ByteBuffer bb = buffer.byteBuffer();
        bb.limit(offset + length);
        bb.position(offset);
        return bb.slice();
    }
}
```

### 4.3 Network Event Loop

The event loop manages all I/O operations:

```java
public class NetworkEventLoop implements Runnable, Component {
    private final Selector selector;
    private final Map<SelectionKey, TcpChannel> channels = new ConcurrentHashMap<>();
    private volatile boolean running;
    private final boolean busySpinMode;

    @Override
    public void run() {
        while (running) {
            try {
                // Drain outgoing ring buffers first
                drainAllRingBuffers();

                // Select for I/O events
                int selected = busySpinMode ?
                    selector.selectNow() :
                    selector.select(selectTimeoutMs);

                if (selected > 0) {
                    processSelectedKeys();
                }

                // Drain again after processing
                drainAllRingBuffers();

                // Execute queued tasks
                runPendingTasks();
            } catch (IOException e) {
                handleError(e);
            }
        }
    }

    private void drainAllRingBuffers() {
        for (TcpChannel channel : channels.values()) {
            channel.drainRingBufferToSocket();
        }
    }

    private void processSelectedKeys() {
        Set<SelectionKey> keys = selector.selectedKeys();
        for (SelectionKey key : keys) {
            if (key.isReadable()) {
                handleRead(key);
            }
            if (key.isWritable()) {
                handleWrite(key);
            }
            if (key.isConnectable()) {
                handleConnect(key);
            }
            if (key.isAcceptable()) {
                handleAccept(key);
            }
        }
        keys.clear();
    }
}
```

### 4.4 Message Sending Pattern

The session layer uses this pattern to send messages:

```java
public class OuchSession {
    private final TcpChannel channel;
    private final OuchMessageFactory messageFactory;

    public String sendEnterOrder(Side side, String symbol, int shares, double price) {
        // 1. Get message from pool
        EnterOrderMessage msg = messageFactory.getMessage(EnterOrderMessage.class);

        // 2. Try to claim ring buffer space
        int claimIndex = channel.tryClaim(msg.getMessageLength());
        if (claimIndex < 0) {
            return null; // Buffer full
        }

        // 3. Wrap message around claimed buffer region
        msg.wrapForWriting(channel.buffer(),
                           ringBuffer.claimedOffset(claimIndex),
                           msg.getMessageLength(),
                           claimIndex);

        // 4. Populate message fields
        String token = generateOrderToken();
        msg.setOrderToken(token)
           .setSide(side)
           .setSymbol(symbol)
           .setShares(shares)
           .setPrice(price)
           .setDefaults();

        // 5. Commit message for sending
        channel.commit(claimIndex);

        return token;
    }
}
```

---

## 5. Session Management

### 5.1 Session State Machine

Define clear state transitions for the protocol:

```java
public enum SessionState {
    CREATED,           // Initial state
    CONNECTING,        // TCP connection in progress
    CONNECTED,         // TCP connected, not logged in
    LOGIN_SENT,        // Login message sent
    LOGGED_IN,         // Fully authenticated
    LOGOUT_SENT,       // Logout initiated
    DISCONNECTED,      // TCP disconnected
    STOPPED;           // Terminated

    public boolean canSendOrders() {
        return this == LOGGED_IN;
    }

    public boolean canReconnect() {
        return this == DISCONNECTED;
    }
}
```

### 5.2 Session Class Structure

```java
public class OuchSession implements NetworkHandler {
    // Identity
    private final String sessionId;
    private final OuchVersion protocolVersion;

    // Network
    private TcpChannel channel;
    private final String host;
    private final int port;
    private final boolean isInitiator;

    // State
    private final AtomicReference<SessionState> state =
        new AtomicReference<>(SessionState.CREATED);
    private final AtomicLong sequenceNumber = new AtomicLong(1);

    // Message handling
    private final OuchMessageFactory messageFactory;
    private final MutableDirectBuffer receiveBuffer;
    private int receivePosition = 0;

    // Listeners
    private final List<SessionStateListener> stateListeners =
        new CopyOnWriteArrayList<>();
    private final List<OuchMessageListener<OuchSession>> messageListeners =
        new CopyOnWriteArrayList<>();

    // NetworkHandler implementation
    @Override
    public void onConnected(TcpChannel channel) {
        this.channel = channel;
        setState(SessionState.CONNECTED);
        if (isInitiator) {
            sendLogin();
        }
    }

    @Override
    public int onDataReceived(TcpChannel channel, DirectBuffer buffer,
                               int offset, int length) {
        // Copy to receive buffer (handle partial messages)
        receiveBuffer.putBytes(receivePosition, buffer, offset, length);
        receivePosition += length;

        // Process complete messages
        int consumed = processReceivedData();

        // Compact buffer
        if (consumed > 0) {
            compactReceiveBuffer(consumed);
        }

        return consumed;
    }

    private int processReceivedData() {
        int processed = 0;

        while (processed < receivePosition) {
            // Check if we have enough for type peek
            if (receivePosition - processed < 1) break;

            OuchMessageType type = messageFactory.peekType(
                receiveBuffer, processed);
            int expectedLength = messageFactory.getExpectedLength(type);

            // Check if complete message available
            if (receivePosition - processed < expectedLength) break;

            // Parse and dispatch message
            OuchMessage msg = messageFactory.readMessage(
                receiveBuffer, processed, expectedLength);
            if (msg != null) {
                dispatchMessage(msg);
            }

            processed += expectedLength;
        }

        return processed;
    }
}
```

### 5.3 Session Scheduler

The scheduler manages session lifecycle based on time:

```java
public class SessionScheduler implements Component {
    private final Map<String, SessionSchedule> schedules = new ConcurrentHashMap<>();
    private final List<ScheduleListener> listeners = new CopyOnWriteArrayList<>();
    private final ClockProvider clockProvider;
    private ScheduledExecutorService scheduler;

    public void addSchedule(SessionSchedule schedule) {
        schedules.put(schedule.getName(), schedule);
    }

    public void addListener(ScheduleListener listener) {
        listeners.add(listener);
    }

    @Override
    public void startActive() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
            this::checkSchedules,
            0,
            checkIntervalMs,
            TimeUnit.MILLISECONDS
        );
    }

    private void checkSchedules() {
        Instant now = clockProvider.instant();

        for (SessionSchedule schedule : schedules.values()) {
            boolean shouldBeActive = schedule.isActiveAt(now);
            boolean wasActive = schedule.wasActiveAt(lastCheck);

            if (shouldBeActive && !wasActive) {
                fireEvent(new ScheduleEvent(schedule, ScheduleEventType.START));
            } else if (!shouldBeActive && wasActive) {
                fireEvent(new ScheduleEvent(schedule, ScheduleEventType.END));
            }

            // Check for reset time
            if (schedule.isResetTime(now)) {
                fireEvent(new ScheduleEvent(schedule, ScheduleEventType.RESET));
            }
        }

        lastCheck = now;
    }
}
```

### 5.4 Session Schedule Configuration

```java
public class SessionSchedule {
    private final String name;
    private final List<TimeWindow> activeWindows;
    private final ZoneId timezone;
    private final ResetSchedule resetSchedule;

    public static Builder builder() {
        return new Builder();
    }

    public boolean isActiveAt(Instant instant) {
        ZonedDateTime zdt = instant.atZone(timezone);
        LocalTime time = zdt.toLocalTime();
        DayOfWeek day = zdt.getDayOfWeek();

        for (TimeWindow window : activeWindows) {
            if (window.contains(time) && window.isActiveOn(day)) {
                return true;
            }
        }
        return false;
    }

    public static class Builder {
        public Builder name(String name);
        public Builder timezone(ZoneId zone);
        public Builder addWindow(LocalTime start, LocalTime end, DayOfWeek... days);
        public Builder resetAt(LocalTime time);
        public SessionSchedule build();
    }
}
```

### 5.5 Centralized Session Management Service

The Session Management Service provides unified session tracking across all protocols (FIX, OUCH, etc.), enabling protocol-agnostic monitoring, control, and state change notifications.

#### Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                  SessionManagementService                        │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ sessions: Map<String, ManagedSession>                    │    │
│  │ listeners: List<SessionStateChangeListener>              │    │
│  └─────────────────────────────────────────────────────────┘    │
│         ▲                                    ▲                   │
│         │ registerSession()                  │ registerSession() │
│  ┌──────┴──────┐                      ┌──────┴──────┐           │
│  │FixSession   │                      │OuchSession  │           │
│  │  Adapter    │                      │  Adapter    │           │
│  └──────┬──────┘                      └──────┬──────┘           │
│         │ wraps                              │ wraps             │
│  ┌──────┴──────┐                      ┌──────┴──────┐           │
│  │  FixSession │                      │ OuchSession │           │
│  └─────────────┘                      └─────────────┘           │
└─────────────────────────────────────────────────────────────────┘
```

#### Common Session Interface

The `ManagedSession` interface provides a unified API for all session types:

```java
public interface ManagedSession {
    // Identity
    String getSessionId();
    String getSessionName();
    String getProtocolType();  // "FIX", "OUCH"

    // Connection State
    boolean isConnected();
    boolean isLoggedOn();
    SessionConnectionState getConnectionState();

    // Session Control
    void enable();
    void disable();
    boolean isEnabled();

    // Sequence Numbers
    long incomingSeqNum();
    long outgoingSeqNum();
    void setIncomingSeqNum(long seqNum);
    void setOutgoingSeqNum(long seqNum);

    // Connection Address
    InetSocketAddress connectionAddress();
    InetSocketAddress connectedAddress();
    void updateConnectionAddress(String host, int port);

    // Access underlying session
    <T> T unwrap();
}
```

#### Unified Connection State

The `SessionConnectionState` enum provides a common state model across protocols:

```java
public enum SessionConnectionState {
    DISCONNECTED,  // Not connected
    CONNECTING,    // Connection in progress
    CONNECTED,     // TCP connected, not logged on
    LOGGED_ON,     // Fully authenticated and operational
    STOPPED;       // Terminated

    public boolean isConnected();
    public boolean isLoggedOn();
    public boolean isTerminal();
}
```

**State Mapping:**

| FIX SessionState | SessionConnectionState |
|------------------|------------------------|
| CREATED, DISCONNECTED | DISCONNECTED |
| CONNECTING | CONNECTING |
| CONNECTED, LOGON_SENT | CONNECTED |
| LOGGED_ON, RESENDING | LOGGED_ON |
| LOGOUT_SENT, STOPPED | STOPPED |

| OUCH SessionState | SessionConnectionState |
|-------------------|------------------------|
| CREATED, DISCONNECTED | DISCONNECTED |
| CONNECTING | CONNECTING |
| CONNECTED, LOGIN_SENT | CONNECTED |
| LOGGED_IN | LOGGED_ON |
| LOGOUT_SENT, STOPPED | STOPPED |

#### Session Management Service Interface

```java
public interface SessionManagementService extends Component {
    // Registration
    void registerSession(ManagedSession session);
    ManagedSession unregisterSession(String sessionId);

    // Lookup
    Optional<ManagedSession> getSession(String sessionId);
    Collection<ManagedSession> getAllSessions();
    Collection<ManagedSession> getSessionsByProtocol(String protocolType);
    Collection<ManagedSession> getConnectedSessions();
    Collection<ManagedSession> getLoggedOnSessions();

    // Bulk Operations
    void enableAllSessions();
    void disableAllSessions();

    // Listeners
    void addStateChangeListener(SessionStateChangeListener listener);
    void removeStateChangeListener(SessionStateChangeListener listener);

    // Statistics
    int getTotalSessionCount();
    int getConnectedSessionCount();
    int getLoggedOnSessionCount();
}
```

#### State Change Notifications

```java
public interface SessionStateChangeListener {
    void onSessionStateChange(ManagedSession session,
                              SessionConnectionState oldState,
                              SessionConnectionState newState);
    default void onSessionRegistered(ManagedSession session) {}
    default void onSessionUnregistered(ManagedSession session) {}
}
```

#### Session Adapters

Each protocol provides an adapter that wraps protocol-specific sessions:

```java
// FIX adapter
public class FixSessionAdapter implements ManagedSession, SessionStateListener {
    private final FixSession session;
    private final DefaultSessionManagementService managementService;

    @Override
    public String getProtocolType() { return "FIX"; }

    @Override
    public long incomingSeqNum() {
        return session.getExpectedIncomingSeqNum();
    }

    @Override
    public long outgoingSeqNum() {
        return session.getOutgoingSeqNum();
    }

    // Maps FIX state to common state
    private static SessionConnectionState mapState(SessionState state) {
        return switch (state) {
            case CREATED, DISCONNECTED -> SessionConnectionState.DISCONNECTED;
            case CONNECTING -> SessionConnectionState.CONNECTING;
            case CONNECTED, LOGON_SENT -> SessionConnectionState.CONNECTED;
            case LOGGED_ON, RESENDING -> SessionConnectionState.LOGGED_ON;
            case LOGOUT_SENT, STOPPED -> SessionConnectionState.STOPPED;
        };
    }
}

// OUCH adapter
public class OuchSessionAdapter implements ManagedSession, OuchSession.SessionStateListener {
    private final OuchSession session;

    @Override
    public String getProtocolType() { return "OUCH"; }

    // OUCH uses single sequence number
    @Override
    public long incomingSeqNum() { return outgoingSeqNum(); }
}
```

#### Engine Integration

Engines automatically register sessions with the management service if available:

```java
public class FixEngine {
    private SessionManagementService sessionManagementService;

    // From ComponentProvider in constructor
    try {
        this.sessionManagementService = provider.getComponent(SessionManagementService.class);
    } catch (IllegalArgumentException e) {
        // Optional dependency
    }

    public FixSession createSession(SessionConfig config) {
        FixSession session = new FixSession(config, logStore);
        // ... setup listeners ...

        // Register with management service
        if (sessionManagementService != null) {
            DefaultSessionManagementService defaultService =
                (sessionManagementService instanceof DefaultSessionManagementService)
                ? (DefaultSessionManagementService) sessionManagementService : null;
            FixSessionAdapter adapter = new FixSessionAdapter(session, defaultService);
            sessionManagementService.registerSession(adapter);
        }

        return session;
    }
}
```

#### Usage Example

```java
// Create and configure service
DefaultSessionManagementService sessionService = new DefaultSessionManagementService();
sessionService.initialize();
sessionService.startActive();

// Register with engines
fixEngine.setSessionManagementService(sessionService);
ouchEngine.setSessionManagementService(sessionService);

// Listen for state changes
sessionService.addStateChangeListener((session, oldState, newState) -> {
    log.info("[{}] {} state: {} -> {}",
        session.getProtocolType(),
        session.getSessionId(),
        oldState,
        newState);
});

// Query sessions
Collection<ManagedSession> allSessions = sessionService.getAllSessions();
Collection<ManagedSession> fixSessions = sessionService.getSessionsByProtocol("FIX");
Collection<ManagedSession> loggedOn = sessionService.getLoggedOnSessions();

// Access underlying session
ManagedSession managed = sessionService.getSession("sender-target").orElseThrow();
FixSession fixSession = managed.unwrap();
```

### 5.6 Session Schedule Association

Sessions can be associated with schedules defined in `SessionScheduler`. This enables automatic session start/stop based on time windows and end-of-day sequence resets.

#### Unified Configuration Pattern

Both FIX and OUCH sessions use the same pattern: specify the schedule name directly in the session configuration.

**FIX Session Configuration:**
```hocon
fix-engine {
    sessions = [
        {
            session-name = "BROKER-EXCHANGE"
            sender-comp-id = "BROKER"
            target-comp-id = "EXCHANGE"
            role = "initiator"
            host = "exchange.example.com"
            port = 9876
            schedule = "us-equities"  # Associates with named schedule
        }
    ]
}
```

**OUCH Session Configuration:**
```hocon
ouch-engine {
    sessions = [
        {
            session-id = "OUCH-NASDAQ"
            host = "ouch.nasdaq.com"
            port = 9200
            schedule = "us-equities"  # Associates with named schedule
        }
    ]
}
```

**Schedule Definition:**
```hocon
schedulers {
    schedules {
        us-equities {
            start-time = "09:30:00"
            end-time = "16:00:00"
            time-zone = "America/New_York"
            reset-time = "17:00:00"
        }
    }
}
```

#### Automatic Association During Session Creation

When sessions are created via `createSession()`, the engine automatically associates them with the specified schedule:

```java
// In FixEngine.createSession(EngineSessionConfig)
public FixSession createSession(EngineSessionConfig config) {
    FixSession session = createSession(convertToSessionConfig(config));

    // Automatic schedule association
    config.getScheduleName().ifPresent(scheduleName -> {
        if (sessionScheduler != null) {
            sessionScheduler.associateSession(session.getConfig().getSessionId(), scheduleName);
            log.info("Associated FIX session '{}' with schedule '{}'",
                    session.getConfig().getSessionId(), scheduleName);
        }
    });

    return session;
}

// In OuchEngine.createSession(OuchSessionConfig)
public OuchSession createSession(OuchSessionConfig config) {
    OuchSession session = new OuchSession(...);
    sessions.put(sessionId, session);

    // Automatic schedule association
    if (scheduler != null && config.getScheduleName() != null) {
        scheduler.associateSession(sessionId, config.getScheduleName());
        log.info("Associated OUCH session '{}' with schedule '{}'",
                sessionId, config.getScheduleName());
    }

    return session;
}
```

#### Schedule Events

Both engines implement `ScheduleListener` to handle schedule events:

| Event | FIX Behavior | OUCH Behavior |
|-------|--------------|---------------|
| `SESSION_START` | Connect initiator sessions | Connect initiator sessions |
| `SESSION_END` | Send Logout, disconnect | Disconnect |
| `RESET_DUE` | Reset sequence numbers, trigger EOD event | Log event (OUCH has no sequence reset) |
| `WARNING_SESSION_END` | Log warning | Log warning |
| `WARNING_RESET` | Log warning | Log warning |

#### Programmatic Schedule Association

Sessions can also be associated programmatically after creation:

```java
// For FIX sessions
fixEngine.associateSessionWithSchedule("SENDER->TARGET", "us-equities");

// For OUCH sessions
ouchEngine.associateSessionWithSchedule("OUCH-NASDAQ", "us-equities");
```

#### Implementation Notes

1. **Optional Dependency**: Engines work without `SessionScheduler`. If no scheduler is configured:
   - Schedule names in session configs are ignored
   - A warning is logged if a schedule is specified but no scheduler exists

2. **Built-in vs External Scheduling**: FIX sessions also support built-in scheduling via `start-time`/`end-time` fields in `SessionConfig`. The `SessionScheduler` provides more flexibility with named schedules, multiple time windows, and centralized management.

3. **Session Creation Order**: Sessions must be created after the `SessionScheduler` is available. When using `ComponentProvider`, ensure the scheduler factory is registered before the engine factory.

---

## 6. Configuration Framework

The configuration framework provides lightweight dependency injection, component lifecycle management, and config-driven component instantiation. It supports both programmatic registration and declarative HOCON-based configuration.

### 6.1 Component Lifecycle

All major components implement the `Component` interface, which supports High Availability (HA) patterns with ACTIVE/STANDBY states:

```java
public interface Component {
    void initialize() throws Exception;   // Load resources, validate config
    void startActive() throws Exception;  // Begin processing
    void startStandby() throws Exception; // Ready but not processing (HA)
    void becomeActive() throws Exception; // Transition from standby to active
    void becomeStandby() throws Exception; // Transition from active to standby
    void stop();                           // Release all resources
    String getName();
    ComponentState getState();
}
```

**Lifecycle State Transitions:**

```
UNINITIALIZED ──► INITIALIZED ──► ACTIVE ◄──► STANDBY
                       │              │           │
                       └──────────────┴───────────┴──► STOPPED
```

### 6.2 LifeCycleComponent

`LifeCycleComponent` orchestrates the lifecycle of multiple components, ensuring proper initialization order and reverse-order shutdown:

```java
public class LifeCycleComponent implements Component {
    private final List<Component> components = new ArrayList<>();

    public LifeCycleComponent addComponent(Component component) {
        components.add(component);
        return this;
    }

    @Override
    public void initialize() throws Exception {
        // Initialize in registration order
        for (Component component : components) {
            component.initialize();
        }
    }

    @Override
    public void stop() {
        // Stop in reverse order for graceful shutdown
        for (int i = components.size() - 1; i >= 0; i--) {
            components.get(i).stop();
        }
    }
}
```

### 6.3 ComponentFactory Interface

The `ComponentFactory` interface defines how components are created:

```java
@FunctionalInterface
public interface ComponentFactory<T extends Component> {
    /**
     * Create a component instance.
     *
     * @param name the component name (may be null)
     * @param config the typesafe config
     * @param provider access to other components
     * @return the created component
     */
    T create(String name, Config config, ComponentProvider provider) throws Exception;
}
```

### 6.4 Creating a ComponentFactory

Each component module provides a factory class following a consistent pattern:

**Simple Factory (no dependencies):**

```java
public class ClockProviderFactory implements ComponentFactory<ClockProvider> {
    @Override
    public ClockProvider create(String name, Config config, ComponentProvider provider) {
        return ClockProvider.system();
    }
}
```

**Factory with Configuration:**

```java
public class NetworkEventLoopFactory implements ComponentFactory<NetworkEventLoop> {
    @Override
    public NetworkEventLoop create(String name, Config config, ComponentProvider provider)
            throws IOException {
        NetworkConfig networkConfig = NetworkConfig.fromConfig(config.getConfig("network"));
        return new NetworkEventLoop(networkConfig, provider);
    }
}
```

**Factory with Dependencies:**

```java
public class SessionSchedulerFactory implements ComponentFactory<SessionScheduler> {
    @Override
    public SessionScheduler create(String name, Config config, ComponentProvider provider) {
        // Get dependency from provider
        ClockProvider clock = provider.getComponent(ClockProvider.class);
        SessionScheduler scheduler = new SessionScheduler(clock);

        if (config.hasPath("schedulers")) {
            SchedulerConfig schedulerConfig = SchedulerConfig.fromConfig(config);
            schedulerConfig.applyTo(scheduler);
        }

        return scheduler;
    }
}
```

**Factory with Multiple Dependencies:**

```java
public class AdminServerFactory implements ComponentFactory<AdminServer> {
    @Override
    public AdminServer create(String name, Config config, ComponentProvider provider) {
        AdminServerConfig adminConfig = AdminServerConfig.fromConfig(config);
        SessionManagementService sessionService = provider.getComponent(SessionManagementService.class);

        AdminServer server = new AdminServer(name != null ? name : "admin-server", adminConfig);
        server.addRouteProvider(new SessionRoutes(sessionService));
        server.addWebSocketHandler(new SessionStateWebSocket(sessionService));

        return server;
    }
}
```

### 6.5 Factory Naming Convention

Factories follow a strict naming convention for auto-discovery:

| Component Type | Factory Class | Location |
|----------------|---------------|----------|
| `ClockProvider` | `ClockProviderFactory` | `config/factory/` |
| `NetworkEventLoop` | `NetworkEventLoopFactory` | `network/factory/` |
| `LogStore` | `LogStoreFactory` | `persistence/factory/` |
| `SessionScheduler` | `SessionSchedulerFactory` | `config/factory/` |
| `SessionManagementService` | `SessionManagementServiceFactory` | `config/factory/` |
| `FixEngine` | `FixEngineFactory` | `fix/engine/factory/` |
| `OuchEngine` | `OuchEngineFactory` | `ouch/engine/factory/` |
| `AdminServer` | `AdminServerFactory` | `admin/factory/` |

### 6.6 ComponentProvider and Registry

`DefaultComponentProvider` implements both `ComponentProvider` and `ComponentRegistry`:

```java
public class DefaultComponentProvider implements ComponentProvider, ComponentRegistry {
    private final Map<Class<? extends Component>, ComponentFactory<?>> factories;
    private final Map<String, Component> instances;
    private final LifeCycleComponent lifeCycle;

    // Register a factory for a component type
    public <T extends Component> void register(Class<T> type, ComponentFactory<T> factory);

    // Get or create a component by type
    public <T extends Component> T getComponent(Class<T> type);

    // Get or create a component by name and type
    public <T extends Component> T getComponent(String name, Class<T> type);

    // Lifecycle management
    public void initialize() throws Exception;
    public void start() throws Exception;
    public void stop();
}
```

### 6.7 Config-Driven Component Loading

Components can be defined declaratively in HOCON configuration with automatic dependency resolution:

#### ComponentDefinition

```java
public class ComponentDefinition {
    private final String name;              // Component name (key in config)
    private final boolean enabled;          // Whether to create this component
    private final String factoryClassName;  // Fully qualified factory class
    private final Class<? extends Component> componentType;
    private final List<String> dependencies; // Names of required components

    public static Map<String, ComponentDefinition> loadAll(Config rootConfig);
}
```

#### HOCON Configuration Format

```hocon
components {
    clock-provider {
        enabled = true
        factory = "com.omnibridge.config.factory.ClockProviderFactory"
        type = "com.omnibridge.config.ClockProvider"
    }

    network {
        enabled = true
        factory = "com.omnibridge.network.factory.NetworkEventLoopFactory"
        type = "com.omnibridge.network.NetworkEventLoop"
        dependencies = ["clock-provider"]
    }

    session-management {
        enabled = true
        factory = "com.omnibridge.config.factory.SessionManagementServiceFactory"
        type = "com.omnibridge.config.session.SessionManagementService"
    }

    ouch-engine {
        enabled = true
        factory = "com.omnibridge.ouch.engine.factory.OuchEngineFactory"
        type = "com.omnibridge.ouch.engine.OuchEngine"
        dependencies = ["network", "clock-provider", "session-management"]
    }

    admin-server {
        enabled = true
        factory = "com.omnibridge.admin.factory.AdminServerFactory"
        type = "com.omnibridge.admin.AdminServer"
        dependencies = ["session-management"]
    }
}
```

#### Automatic Dependency Resolution

`DefaultComponentProvider.loadComponentsFromConfig()` performs:

1. **Parse definitions** - Load all component definitions from config
2. **Filter enabled** - Only process enabled components
3. **Topological sort** - Order by dependencies (Kahn's algorithm)
4. **Register factories** - Instantiate factory classes via reflection
5. **Create components** - Create all components in dependency order

```java
DefaultComponentProvider provider = DefaultComponentProvider.create(configFiles);
provider.loadComponentsFromConfig();  // Parse, sort, register, create
provider.initialize();                 // Initialize all components
provider.start();                      // Start all components
```

### 6.8 Application Lifecycle Hooks

`ApplicationBase` provides lifecycle hooks for applications using config-driven mode:

```java
public abstract class ApplicationBase {

    /**
     * Called before components are initialized.
     * Register additional factories or customize configuration.
     */
    protected void preInitialize() throws Exception { }

    /**
     * Called after all components are initialized but before starting.
     * Components are created but not yet active.
     */
    protected void postInitialize() throws Exception { }

    /**
     * Called before components are started.
     * ADD LISTENERS HERE - before components begin processing.
     */
    protected void preStart() throws Exception { }

    /**
     * Called after all components are started and active.
     * SEND MESSAGES HERE - components are now operational.
     */
    protected void postStart() throws Exception { }
}
```

**Important: Listener vs Message Sending Pattern**

| Hook | Purpose | Example Actions |
|------|---------|-----------------|
| `preStart()` | Configure components before they start | Add `SessionStateListener`, add `MessageListener` |
| `postStart()` | Application logic after components are running | Connect sessions, send orders, run tests |

**Example Implementation:**

```java
public class MyAcceptor extends ApplicationBase {

    @Override
    protected void preStart() throws Exception {
        // Add listeners BEFORE components start
        FixEngine engine = provider.getComponent(FixEngine.class);
        engine.addStateListener(new MyStateListener());
        engine.addMessageListener(new MyMessageListener());
    }

    @Override
    protected void postStart() throws Exception {
        // Send messages AFTER components are running
        FixEngine engine = provider.getComponent(FixEngine.class);
        log.info("Acceptor ready, listening for connections...");
    }
}
```

### 6.9 Configuration Loading

```java
public class ConfigLoader {
    public static Config load(List<String> configFiles) {
        Config config = ConfigFactory.empty();

        // Load reference.conf from classpath
        config = config.withFallback(ConfigFactory.defaultReference());

        // Load each config file
        for (String file : configFiles) {
            Config fileConfig = loadFile(file);
            config = fileConfig.withFallback(config);
        }

        // Override with system properties
        config = ConfigFactory.systemProperties().withFallback(config);

        return config.resolve();
    }
}
```

### 6.10 Complete Configuration Example

```hocon
# Component definitions - processed in dependency order
components {
    clock-provider {
        enabled = true
        factory = "com.omnibridge.config.factory.ClockProviderFactory"
        type = "com.omnibridge.config.ClockProvider"
    }

    network {
        enabled = true
        factory = "com.omnibridge.network.factory.NetworkEventLoopFactory"
        type = "com.omnibridge.network.NetworkEventLoop"
        dependencies = ["clock-provider"]
    }

    session-management {
        enabled = true
        factory = "com.omnibridge.config.factory.SessionManagementServiceFactory"
        type = "com.omnibridge.config.session.SessionManagementService"
    }

    ouch-engine {
        enabled = true
        factory = "com.omnibridge.ouch.engine.factory.OuchEngineFactory"
        type = "com.omnibridge.ouch.engine.OuchEngine"
        dependencies = ["network", "clock-provider", "session-management"]
    }

    admin-server {
        enabled = true
        factory = "com.omnibridge.admin.factory.AdminServerFactory"
        type = "com.omnibridge.admin.AdminServer"
        dependencies = ["session-management"]
    }
}

# Network configuration
network {
    name = "my-network"
    cpu-affinity = -1
    read-buffer-size = 65536
    write-buffer-size = 65536
    select-timeout-ms = 100
    busy-spin-mode = false
}

# Admin API configuration
admin {
    enabled = true
    host = "0.0.0.0"
    port = 8080
    context-path = "/api"
}

# Engine-specific configuration
ouch-engine {
    sessions = [
        {
            session-id = "CLIENT"
            host = "localhost"
            port = 9200
            initiator = true
            protocol-version = "4.2"
            heartbeat-interval = 30
        }
    ]
}

# Persistence (optional)
persistence {
    enabled = false
    path = "data/messages"
}

# Scheduler (optional)
schedulers {
    schedules {
        us-equities {
            start-time = "09:30:00"
            end-time = "16:00:00"
            time-zone = "America/New_York"
            reset-time = "17:00:00"
        }
    }
}
```

### 6.11 Creating a New Component

Follow these steps when adding a new component to the framework:

1. **Implement the Component interface:**

```java
public class MyComponent extends AbstractComponent {
    private final MyConfig config;

    public MyComponent(String name, MyConfig config, ComponentProvider provider) {
        super(name);
        this.config = config;
    }

    @Override
    protected void doInitialize() throws Exception {
        // Load resources
    }

    @Override
    protected void doStartActive() throws Exception {
        // Start processing
    }

    @Override
    protected void doStop() {
        // Release resources
    }
}
```

2. **Create the factory class:**

```java
public class MyComponentFactory implements ComponentFactory<MyComponent> {
    @Override
    public MyComponent create(String name, Config config, ComponentProvider provider) {
        MyConfig myConfig = MyConfig.fromConfig(config.getConfig("my-component"));
        return new MyComponent(name, myConfig, provider);
    }
}
```

3. **Add to configuration:**

```hocon
components {
    my-component {
        enabled = true
        factory = "com.mycompany.MyComponentFactory"
        type = "com.mycompany.MyComponent"
        dependencies = ["network"]  # if any
    }
}

my-component {
    # Component-specific config
    setting1 = "value"
}
```

---

## 7. Admin API

The admin module exposes `SessionManagementService` through REST and WebSocket APIs using Javalin as the HTTP server. This enables external monitoring and control of all protocol sessions.

### Architecture

```
SessionManagementService
         │
         ├──► SessionRoutes (REST API)
         │         │
         │         └──► /api/sessions/*
         │
         └──► SessionStateWebSocket (WebSocket)
                   │
                   ├──► /ws/sessions
                   │
                   └──► implements SessionStateChangeListener
                              │
                              └──► Real-time state change broadcasts
```

### 7.1 REST API

**Base path**: `/api/sessions` (configurable via `admin.context-path`)

#### Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/sessions` | List all sessions |
| `GET` | `/sessions/stats` | Get statistics (total, connected, loggedOn, byProtocol) |
| `GET` | `/sessions/{id}` | Get session details (includes sequence numbers, addresses) |
| `POST` | `/sessions/{id}/enable` | Enable a session |
| `POST` | `/sessions/{id}/disable` | Disable a session |
| `PUT` | `/sessions/{id}/sequence` | Set sequence numbers |
| `POST` | `/sessions/enable-all` | Enable all sessions |
| `POST` | `/sessions/disable-all` | Disable all sessions |
| `GET` | `/sessions/protocol/{type}` | Filter by protocol (FIX, OUCH) |
| `GET` | `/sessions/connected` | Get connected sessions |
| `GET` | `/sessions/logged-on` | Get logged-on sessions |
| `GET` | `/health` | Health check endpoint |

#### Example Responses

**List Sessions** (`GET /api/sessions`):
```json
[
  {
    "sessionId": "CLIENT->EXCHANGE",
    "sessionName": "Trading Client",
    "protocolType": "FIX",
    "state": "LOGGED_ON",
    "connected": true,
    "loggedOn": true,
    "enabled": true
  }
]
```

**Session Statistics** (`GET /api/sessions/stats`):
```json
{
  "total": 4,
  "connected": 2,
  "loggedOn": 2,
  "byProtocol": {
    "FIX": 2,
    "OUCH": 2
  }
}
```

**Session Details** (`GET /api/sessions/{id}`):
```json
{
  "sessionId": "CLIENT->EXCHANGE",
  "sessionName": "Trading Client",
  "protocolType": "FIX",
  "state": "LOGGED_ON",
  "connected": true,
  "loggedOn": true,
  "enabled": true,
  "incomingSeqNum": 42,
  "outgoingSeqNum": 38,
  "connectionAddress": "/192.168.1.100:9876",
  "connectedAddress": "/192.168.1.50:54321"
}
```

**Set Sequence Numbers** (`PUT /api/sessions/{id}/sequence`):
```json
// Request
{ "incomingSeqNum": 100, "outgoingSeqNum": 100 }

// Response
{
  "success": true,
  "sessionId": "CLIENT->EXCHANGE",
  "incomingSeqNum": 100,
  "outgoingSeqNum": 100
}
```

### 7.2 WebSocket API

**Endpoint**: `/ws/sessions` (configurable via `admin.websocket.path`)

The WebSocket API provides real-time session state updates. `SessionStateWebSocket` implements `SessionStateChangeListener` to receive notifications from `SessionManagementService`.

#### Connection Flow

1. **Client connects** → Receives `INITIAL_STATE` with all current sessions
2. **State changes** → Server broadcasts `STATE_CHANGE` to all clients
3. **Session registered** → Server broadcasts `SESSION_REGISTERED`
4. **Session unregistered** → Server broadcasts `SESSION_UNREGISTERED`
5. **Client sends "ping"** → Server responds with `{"type":"PONG"}`

#### Message Types

**INITIAL_STATE** (sent immediately on connection):
```json
{
  "type": "INITIAL_STATE",
  "timestamp": "2026-01-24T10:30:00Z",
  "sessions": [
    {
      "sessionId": "CLIENT->EXCHANGE",
      "sessionName": "Trading Client",
      "protocolType": "FIX",
      "state": "LOGGED_ON",
      "connected": true,
      "loggedOn": true,
      "enabled": true
    }
  ],
  "stats": {
    "total": 2,
    "connected": 1,
    "loggedOn": 1
  }
}
```

**STATE_CHANGE** (broadcast on state transitions):
```json
{
  "type": "STATE_CHANGE",
  "timestamp": "2026-01-24T10:30:05Z",
  "sessionId": "CLIENT->EXCHANGE",
  "sessionName": "Trading Client",
  "protocolType": "FIX",
  "oldState": "CONNECTING",
  "newState": "LOGGED_ON",
  "connected": true,
  "loggedOn": true
}
```

**SESSION_REGISTERED** (broadcast when session is added):
```json
{
  "type": "SESSION_REGISTERED",
  "timestamp": "2026-01-24T10:30:00Z",
  "sessionId": "NEW-SESSION",
  "sessionName": "New Trading Session",
  "protocolType": "OUCH",
  "state": "DISCONNECTED",
  "enabled": true
}
```

**SESSION_UNREGISTERED** (broadcast when session is removed):
```json
{
  "type": "SESSION_UNREGISTERED",
  "timestamp": "2026-01-24T10:35:00Z",
  "sessionId": "OLD-SESSION",
  "sessionName": "Removed Session",
  "protocolType": "FIX"
}
```

#### Real-Time Update Mechanism

```java
// SessionStateWebSocket registers as a listener when server starts
@Override
public void onServerStart() {
    sessionService.addStateChangeListener(this);
}

// Called by SessionManagementService on any state change
@Override
public void onSessionStateChange(ManagedSession session,
                                  SessionConnectionState oldState,
                                  SessionConnectionState newState) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("type", "STATE_CHANGE");
    event.put("sessionId", session.getSessionId());
    event.put("oldState", oldState.toString());
    event.put("newState", newState.toString());
    // ... additional fields

    broadcast(event);  // Send to all connected WebSocket clients
}
```

### 7.3 Configuration

```hocon
# Component definition (required for config-driven mode)
components {
    admin-server {
        enabled = true
        factory = "com.omnibridge.admin.factory.AdminServerFactory"
        type = "com.omnibridge.admin.AdminServer"
        dependencies = ["session-management"]
    }
}

# Admin server settings
admin {
    host = "0.0.0.0"
    port = 8080
    context-path = "/api"
    cors {
        enabled = true
        allowed-origins = ["*"]
    }
    websocket {
        enabled = true
        path = "/ws"
    }
}
```

#### Configuration Options

| Setting | Default | Description |
|---------|---------|-------------|
| `host` | `0.0.0.0` | Bind address |
| `port` | `8080` | HTTP port |
| `context-path` | `/api` | Base path for REST endpoints |
| `cors.enabled` | `false` | Enable CORS |
| `cors.allowed-origins` | `[]` | Allowed origins (`*` for any) |
| `websocket.enabled` | `true` | Enable WebSocket support |
| `websocket.path` | `/ws` | WebSocket base path |

#### Disabling Admin API

For latency-sensitive deployments, disable the admin server by omitting it from the components section:

```hocon
components {
    # admin-server not included - disabled
    clock-provider { ... }
    network { ... }
    fix-engine { ... }
}
```

---

## 8. Testing Infrastructure

### 8.1 Test Script Structure

Create scripts for each test type:

```
connectivity/
├── run-latency-test.bat          # FIX latency
├── run-latency-test.sh
├── run-ouch-latency-test.bat     # OUCH 4.2 latency
├── run-ouch-latency-test.sh
├── run-ouch-latency-test-v50.bat # OUCH 5.0 latency
├── run-ouch-latency-test-v50.sh
├── run-reference-test.bat        # QuickFIX/J interop
├── run-reference-test.sh
├── run-session-test.bat          # Protocol compliance
└── run-session-test.sh
```

### 8.2 Latency Test Script Template

```bash
#!/bin/bash
# Protocol Latency Test Script

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

UBER_JAR="apps/protocol-samples/target/protocol-samples-1.0.0-SNAPSHOT-all.jar"
PORT=9200

# Default parameters
WARMUP_ORDERS=${1:-10000}
TEST_ORDERS=${2:-1000}
RATE=${3:-100}

# JVM options for low latency
JVM_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=10 -XX:+AlwaysPreTouch"

# Start acceptor in background
echo "Starting acceptor..."
java $JVM_OPTS -jar "$UBER_JAR" --acceptor --latency > acceptor.log 2>&1 &
ACCEPTOR_PID=$!

# Wait for acceptor to start
sleep 3

# Run initiator in latency mode
echo "Running latency test..."
java $JVM_OPTS -cp "$UBER_JAR" com.example.initiator.SampleInitiator \
    --latency \
    --warmup-orders $WARMUP_ORDERS \
    --test-orders $TEST_ORDERS \
    --rate $RATE

# Cleanup
kill $ACCEPTOR_PID 2>/dev/null
```

### 8.3 Latency Tracker

```java
public class LatencyTracker {
    private final long[] sendTimes;
    private final long[] latencies;
    private final AtomicInteger sentCount = new AtomicInteger(0);
    private final AtomicInteger receivedCount = new AtomicInteger(0);

    public LatencyTracker(int capacity) {
        this.sendTimes = new long[capacity];
        this.latencies = new long[capacity];
    }

    public int recordSend() {
        int idx = sentCount.getAndIncrement();
        if (idx < sendTimes.length) {
            sendTimes[idx] = System.nanoTime();
        }
        return idx;
    }

    public void recordReceive(int index) {
        if (index < latencies.length) {
            latencies[index] = System.nanoTime() - sendTimes[index];
            receivedCount.incrementAndGet();
        }
    }

    public void printStatistics() {
        int count = receivedCount.get();
        long[] sorted = Arrays.copyOf(latencies, count);
        Arrays.sort(sorted);

        System.out.println("Latency Statistics (microseconds):");
        System.out.println("  Min:    " + sorted[0] / 1000);
        System.out.println("  Max:    " + sorted[count-1] / 1000);
        System.out.println("  Avg:    " + average(sorted) / 1000);
        System.out.println("  P50:    " + sorted[count * 50 / 100] / 1000);
        System.out.println("  P90:    " + sorted[count * 90 / 100] / 1000);
        System.out.println("  P95:    " + sorted[count * 95 / 100] / 1000);
        System.out.println("  P99:    " + sorted[count * 99 / 100] / 1000);
        System.out.println("  P99.9:  " + sorted[count * 999 / 1000] / 1000);
    }
}
```

### 8.4 Session Test Framework

```java
public abstract class SessionTest {
    protected final TestContext context;

    public abstract String getName();
    public abstract void run() throws Exception;

    protected void send(OuchMessage message) {
        context.getSession().send(message);
    }

    protected OuchMessage waitForResponse(long timeoutMs) throws Exception {
        return context.getResponseQueue().poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    protected void assertMessageType(OuchMessage msg, OuchMessageType expected) {
        if (msg.getMessageType() != expected) {
            throw new AssertionError("Expected " + expected + " but got " + msg.getMessageType());
        }
    }
}

public class HeartbeatTest extends SessionTest {
    @Override
    public String getName() {
        return "HeartbeatTest";
    }

    @Override
    public void run() throws Exception {
        // Wait for heartbeat interval
        Thread.sleep(heartbeatIntervalMs + 1000);

        // Should receive heartbeat or test request
        OuchMessage msg = waitForResponse(5000);
        assertNotNull(msg, "No heartbeat received");
    }
}
```

### 8.5 Reference Testing (QuickFIX/J)

For FIX protocol, test against QuickFIX/J:

```java
public class ReferenceTest {
    private quickfix.Application quickfixApp;
    private FixEngine ourEngine;

    @Test
    public void testHeartbeat() throws Exception {
        // Start our engine as initiator
        ourEngine.connect("TEST", "localhost", 9876);

        // Wait for session
        waitForLogon(5000);

        // QuickFIX/J should receive heartbeat
        verify(quickfixApp).fromAdmin(any(Heartbeat.class), any());
    }
}
```

---

## 9. Multi-Version Protocol Support

### 9.1 Version Enum

Define protocol versions with their characteristics:

```java
public enum OuchVersion {
    V42("4.2", false, 14, "OrderToken"),
    V50("5.0", true, 4, "UserRefNum");

    private final String displayName;
    private final boolean supportsAppendages;
    private final int orderIdLength;
    private final String orderIdFieldName;

    public boolean supportsAppendages() {
        return supportsAppendages;
    }

    public int getOrderIdLength() {
        return orderIdLength;
    }
}
```

### 9.2 Version-Specific Message Classes

Organize by version in separate packages:

```
ouch/message/
├── OuchMessage.java           # Abstract base
├── OuchVersion.java           # Version enum
├── OuchMessageType.java       # Message type enum
├── EnterOrderMessage.java     # Base class (V42 layout)
├── v42/
│   ├── V42EnterOrderMessage.java    # Extends EnterOrderMessage
│   ├── V42OrderAcceptedMessage.java
│   ├── V42MessageReader.java
│   ├── V42MessageFactory.java
│   └── V42MessagePool.java
└── v50/
    ├── V50OuchMessage.java          # Base with appendage support
    ├── V50EnterOrderMessage.java    # Different layout (UserRefNum)
    ├── V50OrderAcceptedMessage.java
    ├── V50MessageReader.java
    ├── V50MessageFactory.java
    ├── V50MessagePool.java
    └── appendage/
        ├── Appendage.java
        ├── AppendageType.java
        └── PegAppendage.java
```

### 9.3 V50 Base Class with Appendages

```java
public abstract class V50OuchMessage extends OuchMessage {
    protected List<Appendage> appendages;
    protected int appendageCount;
    protected int appendagesLength;

    @Override
    public OuchVersion getVersion() {
        return OuchVersion.V50;
    }

    public abstract int getBaseMessageLength();
    public abstract int getAppendageCountOffset();

    @Override
    public int getMessageLength() {
        return getBaseMessageLength() + appendagesLength;
    }

    public int getAppendageCount() {
        if (getAppendageCountOffset() < 0) return 0;
        return getUnsignedByte(getAppendageCountOffset());
    }

    public <T extends Appendage> T getAppendage(AppendageType type) {
        for (Appendage appendage : getAppendages()) {
            if (appendage.getType() == type) {
                return (T) appendage;
            }
        }
        return null;
    }

    public V50OuchMessage addAppendage(Appendage appendage) {
        if (appendages == null) {
            appendages = new ArrayList<>();
        }
        appendages.add(appendage);
        appendageCount++;
        appendagesLength += appendage.getTotalLength();
        return this;
    }

    @Override
    public int complete() {
        // Write appendage count
        if (getAppendageCountOffset() >= 0) {
            putByte(getAppendageCountOffset(), (byte) appendageCount);
        }
        // Write appendages
        // ...
        return getBaseMessageLength() + appendagesLength;
    }
}
```

### 9.4 Version-Specific Listener Callbacks

```java
public interface OuchMessageListener<S> {
    // V42 callbacks (base types)
    default void onOrderAccepted(S session, OrderAcceptedMessage msg) {}
    default void onOrderExecuted(S session, OrderExecutedMessage msg) {}
    default void onEnterOrder(S session, EnterOrderMessage msg) {}

    // V50 callbacks (version-specific types)
    default void onOrderAcceptedV50(S session, V50OrderAcceptedMessage msg) {}
    default void onOrderExecutedV50(S session, V50OrderExecutedMessage msg) {}
    default void onEnterOrderV50(S session, V50EnterOrderMessage msg) {}

    // Generic callback for all messages
    default void onMessage(S session, OuchMessage msg) {}
}
```

### 9.5 Version-Aware Dispatching

```java
private void processIncomingMessage(OuchMessage msg) {
    boolean isV50 = msg.getVersion() == OuchVersion.V50;

    switch (msg.getMessageType()) {
        case ORDER_ACCEPTED -> {
            if (isV50) {
                var m = (V50OrderAcceptedMessage) msg;
                notifyListeners(l -> l.onOrderAcceptedV50(this, m));
            } else {
                var m = (OrderAcceptedMessage) msg;
                notifyListeners(l -> l.onOrderAccepted(this, m));
            }
        }
        // ... other message types
    }

    // Always call generic handler
    notifyListeners(l -> l.onMessage(this, msg));
}
```

---

## 10. Creating a New Protocol

### 10.1 Checklist

Follow these steps to implement a new exchange protocol:

1. **Create Module Structure**
   ```
   new-protocol/
   ├── pom.xml                    # Parent POM
   ├── message/
   │   ├── pom.xml
   │   └── src/main/java/.../
   │       ├── NewProtocolMessage.java
   │       ├── MessageType.java
   │       ├── MessageReader.java
   │       ├── MessageFactory.java
   │       └── MessagePool.java
   └── engine/
       ├── pom.xml
       └── src/main/java/.../
           ├── NewProtocolEngine.java
           ├── NewProtocolSession.java
           └── SessionState.java
   ```

2. **Define Message Classes**
   - Create base message class extending appropriate parent
   - Define field offsets as static constants
   - Implement getters/setters using buffer operations
   - Create message reader with flyweight instances
   - Create message pool for object reuse
   - Create factory for version abstraction

3. **Implement Session Layer**
   - Define state machine
   - Implement NetworkHandler interface
   - Handle login/logout/heartbeat
   - Manage sequence numbers
   - Implement message dispatching

4. **Create Engine**
   - Manage multiple sessions
   - Integrate network event loop
   - Integrate persistence
   - Integrate scheduler

5. **Create Sample Apps**
   - Acceptor application
   - Initiator application
   - Configuration files
   - Launch scripts

6. **Create Test Infrastructure**
   - Latency test script
   - Session tests
   - Reference tests (if available)

### 10.2 Example: New Binary Protocol

```java
// 1. Define message type enum
public enum NewProtocolMessageType {
    LOGIN('L'),
    LOGIN_ACK('A'),
    ORDER('O'),
    ORDER_ACK('K'),
    HEARTBEAT('H');

    private final char code;

    public static NewProtocolMessageType fromCode(byte code) {
        for (var type : values()) {
            if (type.code == (char) code) return type;
        }
        return null;
    }
}

// 2. Create base message class
public abstract class NewProtocolMessage {
    protected DirectBuffer buffer;
    protected int offset;
    protected int length;

    public abstract NewProtocolMessageType getMessageType();
    public abstract int getMessageLength();

    // Standard helper methods...
}

// 3. Create specific message class
public class OrderMessage extends NewProtocolMessage {
    public static final int MSG_TYPE_OFFSET = 0;
    public static final int ORDER_ID_OFFSET = 1;
    public static final int SYMBOL_OFFSET = 9;
    public static final int QUANTITY_OFFSET = 17;
    public static final int PRICE_OFFSET = 21;
    public static final int MESSAGE_LENGTH = 29;

    @Override
    public NewProtocolMessageType getMessageType() {
        return NewProtocolMessageType.ORDER;
    }

    public long getOrderId() {
        return buffer.getLong(offset + ORDER_ID_OFFSET, ByteOrder.BIG_ENDIAN);
    }

    public OrderMessage setOrderId(long orderId) {
        ((MutableDirectBuffer) buffer).putLong(
            offset + ORDER_ID_OFFSET, orderId, ByteOrder.BIG_ENDIAN);
        return this;
    }

    // ... other fields
}

// 4. Create session class
public class NewProtocolSession implements NetworkHandler {
    private final AtomicReference<SessionState> state =
        new AtomicReference<>(SessionState.CREATED);

    @Override
    public int onDataReceived(TcpChannel channel, DirectBuffer buffer,
                               int offset, int length) {
        // Parse and dispatch messages
        return processData(buffer, offset, length);
    }
}
```

---

## 11. Performance Considerations

### 11.1 Zero-Copy Techniques

1. **Flyweight Messages**: Wrap buffers, don't copy
2. **Ring Buffer Direct View**: ByteBuffer.slice() for socket writes
3. **Avoid String Operations**: Use byte comparisons where possible
4. **Pre-allocated Buffers**: Reuse buffers across messages

### 11.2 Lock-Free Data Structures

1. **ManyToOneRingBuffer**: Multi-producer, single-consumer
2. **AtomicReference**: State machine transitions
3. **AtomicInteger/Long**: Sequence numbers, counters
4. **CopyOnWriteArrayList**: Listener lists

### 11.3 Memory Layout

1. **Direct Buffers**: Use ByteBuffer.allocateDirect()
2. **Off-Heap**: Agrona's UnsafeBuffer
3. **Cache Alignment**: Consider field ordering
4. **Minimize Allocations**: Object pools, ThreadLocal

### 11.4 JVM Tuning

```bash
JVM_OPTS="-Xms256m -Xmx512m \
          -XX:+UseG1GC \
          -XX:MaxGCPauseMillis=10 \
          -XX:+AlwaysPreTouch \
          -Dagrona.disable.bounds.checks=true"
```

### 11.5 Latency Targets

| Percentile | Target |
|------------|--------|
| P50 | < 100 microseconds |
| P90 | < 200 microseconds |
| P99 | < 500 microseconds |
| P99.9 | < 1 millisecond |
| Max | < 5 milliseconds |

---

## Appendix A: Key Classes Reference

### Core Framework

| Module | Class | Purpose |
|--------|-------|---------|
| Config | Component | Lifecycle interface with HA support |
| Config | ComponentState | Lifecycle state enum (UNINITIALIZED, INITIALIZED, ACTIVE, STANDBY, STOPPED) |
| Config | ComponentFactory | Factory interface for creating components |
| Config | ComponentProvider | Interface for accessing components |
| Config | ComponentRegistry | Interface for registering factories |
| Config | ComponentDefinition | Parses component definitions from HOCON |
| Config | DefaultComponentProvider | Full implementation with config-driven loading |
| Config | LifeCycleComponent | Orchestrates multiple component lifecycles |
| Config | AbstractComponent | Base class for components |
| Config | ConfigLoader | HOCON configuration loading |

### Factory Classes

| Module | Factory Class | Creates |
|--------|---------------|---------|
| Config | ClockProviderFactory | ClockProvider |
| Config | SessionSchedulerFactory | SessionScheduler |
| Config | SessionManagementServiceFactory | SessionManagementService |
| Network | NetworkEventLoopFactory | NetworkEventLoop |
| Persistence | LogStoreFactory | LogStore (MemoryMappedLogStore) |
| FIX Engine | FixEngineFactory | FixEngine |
| OUCH Engine | OuchEngineFactory | OuchEngine |
| Admin | AdminServerFactory | AdminServer |

### Session Management

| Module | Class | Purpose |
|--------|-------|---------|
| Config | SessionScheduler | Session timing and scheduling |
| Config | SessionManagementService | Unified session registry interface |
| Config | DefaultSessionManagementService | Thread-safe session management impl |
| Config | ManagedSession | Protocol-agnostic session interface |
| Config | SessionConnectionState | Common session state enum |
| Config | SessionStateChangeListener | State change notifications |

### Network Layer

| Module | Class | Purpose |
|--------|-------|---------|
| Network | NetworkEventLoop | NIO event loop |
| Network | TcpChannel | Per-connection ring buffer |
| Network | NetworkHandler | I/O callbacks |

### Protocol Engines

| Module | Class | Purpose |
|--------|-------|---------|
| FIX Message | IncomingFixMessage | Flyweight parser |
| FIX Message | RingBufferOutgoingMessage | Zero-copy encoder |
| FIX Engine | FixSession | Session state machine |
| FIX Engine | FixSessionAdapter | ManagedSession wrapper for FIX |
| FIX Engine | FixEngine | Multi-session manager |
| OUCH Message | OuchMessage | Base flyweight |
| OUCH Message | V42*/V50* | Version-specific messages |
| OUCH Engine | OuchSession | Session state machine |
| OUCH Engine | OuchSessionAdapter | ManagedSession wrapper for OUCH |
| OUCH Engine | OuchEngine | Multi-session manager |

### Admin API

| Module | Class | Purpose |
|--------|-------|---------|
| Admin | AdminServer | Javalin-based HTTP/WebSocket server |
| Admin | AdminServerConfig | Server configuration (host, port, CORS, etc.) |
| Admin | RouteProvider | Interface for REST route registration |
| Admin | SessionRoutes | REST endpoints for session management |
| Admin | WebSocketHandler | Interface for WebSocket handlers |
| Admin | SessionStateWebSocket | Real-time session state updates via WebSocket |

### Applications

| Module | Class | Purpose |
|--------|-------|---------|
| Apps Common | ApplicationBase | Base class with lifecycle hooks |
| Apps Common | LatencyTracker | Latency measurement utility |

---

## Appendix B: File Paths Quick Reference

```
/config/src/main/java/com/omnibridge/config/
    Component.java              # Lifecycle interface
    ComponentState.java         # State enum
    ComponentFactory.java       # Factory interface
    ComponentDefinition.java    # Config-driven component definition
    AbstractComponent.java      # Base component implementation
    LifeCycleComponent.java     # Multi-component orchestrator
    ConfigLoader.java           # HOCON loading

/config/src/main/java/com/omnibridge/config/provider/
    ComponentProvider.java      # Component access interface
    ComponentRegistry.java      # Factory registration interface
    DefaultComponentProvider.java # Full implementation

/config/src/main/java/com/omnibridge/config/factory/
    ClockProviderFactory.java
    SessionSchedulerFactory.java
    SessionManagementServiceFactory.java

/config/src/main/java/com/omnibridge/config/session/
    SessionManagementService.java
    DefaultSessionManagementService.java
    ManagedSession.java
    SessionConnectionState.java
    SessionStateChangeListener.java

/network/src/main/java/com/omnibridge/network/
    NetworkEventLoop.java, TcpChannel.java, NetworkHandler.java

/network/src/main/java/com/omnibridge/network/factory/
    NetworkEventLoopFactory.java

/persistence/src/main/java/com/omnibridge/persistence/
    LogStore.java, MemoryMappedLogStore.java

/persistence/src/main/java/com/omnibridge/persistence/factory/
    LogStoreFactory.java

/admin/src/main/java/com/omnibridge/admin/
    AdminServer.java             # HTTP/WebSocket server component
    config/AdminServerConfig.java

/admin/src/main/java/com/omnibridge/admin/factory/
    AdminServerFactory.java

/admin/src/main/java/com/omnibridge/admin/routes/
    RouteProvider.java           # Interface for REST route providers
    SessionRoutes.java           # Session management REST endpoints

/admin/src/main/java/com/omnibridge/admin/websocket/
    WebSocketHandler.java        # Interface for WebSocket handlers
    SessionStateWebSocket.java   # Real-time session state updates

/fix/message/src/main/java/com/omnibridge/fix/message/
    IncomingFixMessage.java, RingBufferOutgoingMessage.java, FixReader.java

/fix/engine/src/main/java/com/omnibridge/fix/engine/
    FixEngine.java
    session/FixSession.java
    session/FixSessionAdapter.java

/fix/engine/src/main/java/com/omnibridge/fix/engine/factory/
    FixEngineFactory.java

/ouch/message/src/main/java/com/omnibridge/ouch/message/
    OuchMessage.java, OuchVersion.java
    v42/*.java, v50/*.java

/ouch/engine/src/main/java/com/omnibridge/ouch/engine/
    OuchEngine.java
    session/OuchSession.java
    session/OuchSessionAdapter.java

/ouch/engine/src/main/java/com/omnibridge/ouch/engine/factory/
    OuchEngineFactory.java

/apps/common/src/main/java/com/omnibridge/apps/common/
    ApplicationBase.java        # Application lifecycle hooks
    LatencyTracker.java
```

---

*Document Version: 1.4*
*Last Updated: January 2026*
*Changes:
- Added Section 7: Admin API with REST and WebSocket documentation
- Expanded Section 6 with comprehensive ComponentFactory framework documentation
- Added config-driven component loading with dependency resolution
- Added application lifecycle hooks (preStart/postStart) pattern
- Updated Appendices A and B with factory classes, admin classes, and file paths*
