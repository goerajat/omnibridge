# Exchange Protocol Implementation Architecture

This document describes the architecture, design patterns, and code structure used to implement FIX and OUCH protocols in this ultra-low latency trading engine. Use this as a reference guide when implementing new exchange protocols.

## Table of Contents

1. [Overview](#1-overview)
2. [Module Structure](#2-module-structure)
3. [Message Encoding and Decoding](#3-message-encoding-and-decoding)
4. [Network Layer and Ring Buffers](#4-network-layer-and-ring-buffers)
5. [Session Management](#5-session-management)
   - [5.5 Centralized Session Management Service](#55-centralized-session-management-service)
6. [Configuration Framework](#6-configuration-framework)
7. [Testing Infrastructure](#7-testing-infrastructure)
8. [Multi-Version Protocol Support](#8-multi-version-protocol-support)
9. [Creating a New Protocol](#9-creating-a-new-protocol)
10. [Performance Considerations](#10-performance-considerations)

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

---

## 6. Configuration Framework

### 6.1 Component Lifecycle

All major components implement the Component interface:

```java
public interface Component {
    enum State {
        UNINITIALIZED,
        INITIALIZED,
        ACTIVE,
        STANDBY,
        STOPPED
    }

    void initialize() throws Exception;
    void startActive() throws Exception;
    void startStandby() throws Exception;
    void stop() throws Exception;
    State getState();
}
```

### 6.2 ComponentProvider

Dependency injection without heavy frameworks:

```java
public interface ComponentProvider {
    <T> T getComponent(Class<T> type);
    <T> T getComponent(String name, Class<T> type);
    void register(Class<?> type, ComponentFactory<?> factory);
}

public class DefaultComponentProvider implements ComponentProvider {
    private final Map<Class<?>, Object> components = new ConcurrentHashMap<>();
    private final Map<Class<?>, ComponentFactory<?>> factories = new ConcurrentHashMap<>();
    private final Config config;

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getComponent(Class<T> type) {
        return (T) components.computeIfAbsent(type, this::createComponent);
    }

    private Object createComponent(Class<?> type) {
        ComponentFactory<?> factory = factories.get(type);
        if (factory == null) {
            throw new IllegalArgumentException("No factory for: " + type);
        }
        return factory.create(type.getSimpleName(), config, this);
    }
}
```

### 6.3 Configuration Loading

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

    private static Config loadFile(String path) {
        File file = new File(path);
        if (file.exists()) {
            return ConfigFactory.parseFile(file);
        }
        // Try classpath
        return ConfigFactory.parseResources(path);
    }
}
```

### 6.4 Sample Configuration (HOCON)

```hocon
ouch-engine {
    network {
        io-threads = 1
        busy-spin = false
        cpu-affinity = -1  # -1 for none, 0+ for specific core
    }

    sessions = [
        {
            session-id = "CLIENT"
            host = "localhost"
            port = 9200
            initiator = true
            protocol-version = "4.2"  # or "5.0"
            heartbeat-interval = 30
        }
    ]
}

persistence {
    enabled = true
    path = "data/ouch"
}

schedule {
    sessions = [
        {
            name = "US_EQUITIES"
            timezone = "America/New_York"
            windows = [
                { start = "09:30", end = "16:00", days = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"] }
            ]
            reset-time = "17:00"
        }
    ]
}
```

---

## 7. Testing Infrastructure

### 7.1 Test Script Structure

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

### 7.2 Latency Test Script Template

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

### 7.3 Latency Tracker

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

### 7.4 Session Test Framework

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

### 7.5 Reference Testing (QuickFIX/J)

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

## 8. Multi-Version Protocol Support

### 8.1 Version Enum

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

### 8.2 Version-Specific Message Classes

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

### 8.3 V50 Base Class with Appendages

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

### 8.4 Version-Specific Listener Callbacks

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

### 8.5 Version-Aware Dispatching

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

## 9. Creating a New Protocol

### 9.1 Checklist

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

### 9.2 Example: New Binary Protocol

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

## 10. Performance Considerations

### 10.1 Zero-Copy Techniques

1. **Flyweight Messages**: Wrap buffers, don't copy
2. **Ring Buffer Direct View**: ByteBuffer.slice() for socket writes
3. **Avoid String Operations**: Use byte comparisons where possible
4. **Pre-allocated Buffers**: Reuse buffers across messages

### 10.2 Lock-Free Data Structures

1. **ManyToOneRingBuffer**: Multi-producer, single-consumer
2. **AtomicReference**: State machine transitions
3. **AtomicInteger/Long**: Sequence numbers, counters
4. **CopyOnWriteArrayList**: Listener lists

### 10.3 Memory Layout

1. **Direct Buffers**: Use ByteBuffer.allocateDirect()
2. **Off-Heap**: Agrona's UnsafeBuffer
3. **Cache Alignment**: Consider field ordering
4. **Minimize Allocations**: Object pools, ThreadLocal

### 10.4 JVM Tuning

```bash
JVM_OPTS="-Xms256m -Xmx512m \
          -XX:+UseG1GC \
          -XX:MaxGCPauseMillis=10 \
          -XX:+AlwaysPreTouch \
          -Dagrona.disable.bounds.checks=true"
```

### 10.5 Latency Targets

| Percentile | Target |
|------------|--------|
| P50 | < 100 microseconds |
| P90 | < 200 microseconds |
| P99 | < 500 microseconds |
| P99.9 | < 1 millisecond |
| Max | < 5 milliseconds |

---

## Appendix A: Key Classes Reference

| Component | Class | Purpose |
|-----------|-------|---------|
| Config | ConfigLoader | HOCON configuration loading |
| Config | Component | Lifecycle interface |
| Config | ComponentProvider | Dependency injection |
| Config | SessionScheduler | Session timing |
| Config | SessionManagementService | Unified session registry interface |
| Config | DefaultSessionManagementService | Thread-safe session management impl |
| Config | ManagedSession | Protocol-agnostic session interface |
| Config | SessionConnectionState | Common session state enum |
| Config | SessionStateChangeListener | State change notifications |
| Network | NetworkEventLoop | NIO event loop |
| Network | TcpChannel | Per-connection ring buffer |
| Network | NetworkHandler | I/O callbacks |
| Persistence | LogStore | Message logging interface |
| Persistence | MemoryMappedLogStore | Chronicle Queue impl |
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

---

## Appendix B: File Paths Quick Reference

```
/config/src/main/java/com/omnibridge/config/
    ConfigLoader.java, Component.java, ComponentProvider.java

/config/src/main/java/com/omnibridge/config/session/
    SessionManagementService.java      # Service interface
    DefaultSessionManagementService.java # Thread-safe implementation
    ManagedSession.java                # Unified session interface
    SessionConnectionState.java        # Common state enum
    SessionStateChangeListener.java    # State change notifications

/network/src/main/java/com/omnibridge/network/
    NetworkEventLoop.java, TcpChannel.java, NetworkHandler.java

/persistence/src/main/java/com/omnibridge/persistence/
    LogStore.java, MemoryMappedLogStore.java

/fix/message/src/main/java/com/omnibridge/message/
    IncomingFixMessage.java, RingBufferOutgoingMessage.java, FixReader.java

/fix/engine/src/main/java/com/omnibridge/engine/
    FixEngine.java
    session/FixSession.java
    session/FixSessionAdapter.java     # ManagedSession adapter

/ouch/message/src/main/java/com/omnibridge/ouch/message/
    OuchMessage.java, OuchVersion.java
    v42/*.java, v50/*.java

/ouch/engine/src/main/java/com/omnibridge/ouch/engine/
    OuchEngine.java
    session/OuchSession.java
    session/OuchSessionAdapter.java    # ManagedSession adapter

/apps/common/src/main/java/
    LatencyTracker.java, ApplicationBase.java
```

---

*Document Version: 1.1*
*Last Updated: January 2026*
*Changes: Added Section 5.5 - Centralized Session Management Service*
