# Investigation: OneToOneRingBuffer for Socket Reads

## Current Architecture

```
Socket → TcpChannel.readBuffer (64KB) → FixReader.accumulationBuffer (64KB)
       → IncomingFixMessage (wraps buffer) → Listeners (same thread)
```

**Key characteristics:**
- All processing happens on NetworkEventLoop thread
- FixReader accumulates partial messages until complete
- IncomingFixMessage wraps buffer directly (zero-copy)
- IncomingMessagePool: 64 pre-allocated messages
- Listeners invoked synchronously on event loop thread

## Proposed Architecture

```
NetworkEventLoop Thread                    Worker Thread
        │                                       │
Socket → TcpChannel.readBuffer                  │
        │                                       │
        ↓                                       │
FixReader (parse complete message)              │
        │                                       │
        ↓                                       │
OneToOneRingBuffer ─────────────────────► Poll ring buffer
(shared by all channels)                        │
        │                                       ↓
        │                             IncomingFixMessage.wrap()
        │                                       │
        │                                       ↓
        │                             Invoke listeners
        │                                       │
        │                                       ↓
        │                             Release record
        └───────────────────────────────────────┘
```

**Goals:**
1. Move listener invocation off the event loop thread
2. Enable the event loop to continue reading while listeners process
3. Maintain zero-copy semantics where possible
4. Support backpressure when worker can't keep up

## Design Challenges

### Challenge 1: FIX Message Framing

FIX messages don't have a length prefix. The length is determined by:
1. Finding `8=FIX.x.x|` header
2. Parsing tag 9 (BodyLength) value
3. Calculating total = header + body + 7 (checksum)

**Implication**: We cannot claim exact size in ring buffer before reading from socket.

### Challenge 2: Partial Reads

TCP may deliver partial messages:
- Read 1: `8=FIX.4.4|9=100|35=D|...` (incomplete)
- Read 2: `...10=123|` (rest of message)

**Implication**: Need accumulation buffer before committing to ring buffer.

### Challenge 3: Multiple Channels Sharing One Ring Buffer

With multiple TcpChannels per NetworkEventLoop, messages interleave:
- Channel A: Message 1, Message 2
- Channel B: Message 3
- Ring buffer order: Msg1, Msg3, Msg2 (interleaved)

**Implication**: Each record needs channel/session identifier for worker to route.

### Challenge 4: Ring Buffer Record Lifecycle

OneToOneRingBuffer records are reclaimed after `read()` returns.
Worker thread must not hold references to buffer data after read callback.

**Implication**: Either copy data or use `controlledRead()` with explicit release.

## Design Options

### Option A: Copy Complete Messages to Ring Buffer

```
FixReader (existing) → Complete message → Copy to ring buffer → Worker consumes
```

**Pros:**
- Minimal changes to existing parsing logic
- Simple ownership model - worker owns copy
- FixReader accumulation handles partial messages

**Cons:**
- One copy per message (~200-500 bytes typical)
- Not truly zero-copy

**Implementation:**
1. Keep existing TcpChannel.readBuffer and FixReader
2. When `readIncomingMessage()` returns true, copy to ring buffer
3. Worker thread polls ring buffer, creates IncomingFixMessage wrapper

### Option B: Direct Read into Ring Buffer with Pending Slot

```
TcpChannel claims maxMessageLength → Read directly → Parse in-place → Commit actual size
```

**Pros:**
- Zero-copy from socket to ring buffer
- No intermediate accumulation

**Cons:**
- Slot is "locked" until message complete (blocks other messages)
- Complex partial message handling
- Wastes space if messages are smaller than maxMessageLength

**Implementation:**
1. TcpChannel claims `maxMessageLength + HEADER_SIZE` at start
2. Reads directly into claimed region
3. FixReader parses in-place
4. Commits with actual message length when complete
5. Claims next slot

### Option C: Chunk-Based Ring Buffer (Complex)

```
Ring buffer stores fixed-size chunks (4KB)
Chain chunks for large messages
Worker reassembles chunks
```

**Pros:**
- No wasted space
- Handles variable message sizes

**Cons:**
- Very complex implementation
- Fragmentation issues
- Harder to achieve zero-copy for worker

### Recommendation: Option A (Copy Complete Messages)

**Rationale:**
1. Copy overhead is minimal for FIX messages (200-500 bytes average)
2. Maintains clean separation of concerns
3. FixReader's accumulation logic is battle-tested
4. Simplest to implement correctly
5. Can optimize later if copy becomes bottleneck

## Detailed Design (Option A)

### Ring Buffer Message Layout

```
┌──────────────────┬───────────────────┬─────────────────────┐
│ Record Header    │ Message Header    │ FIX Message Data    │
│ (Agrona, 8 bytes)│ (16 bytes)        │ (N bytes)           │
└──────────────────┴───────────────────┴─────────────────────┘
                   │                   │
                   │ sessionId (8B)    │ Raw FIX bytes
                   │ reserved (8B)     │ "8=FIX.4.4|..."
```

**Message Header Fields:**
- `sessionId` (8 bytes): Unique identifier for FixSession (pointer or hash)
- `reserved` (8 bytes): Future use (timestamp, flags, etc.)

### New Classes

#### 1. `IncomingRingBuffer.java` (fix-network-io)

```java
public class IncomingRingBuffer {
    private static final int MSG_TYPE_ID = 1;
    private static final int HEADER_SIZE = 16; // sessionId + reserved

    private final OneToOneRingBuffer ringBuffer;
    private final UnsafeBuffer claimBuffer;

    public IncomingRingBuffer(int capacity) {
        // capacity must be power of 2
        int totalSize = capacity + RingBufferDescriptor.TRAILER_LENGTH;
        ByteBuffer backing = ByteBuffer.allocateDirect(totalSize);
        this.ringBuffer = new OneToOneRingBuffer(new UnsafeBuffer(backing));
        this.claimBuffer = new UnsafeBuffer();
    }

    /**
     * Writes a complete FIX message to the ring buffer.
     * Called by NetworkEventLoop thread after FixReader parses complete message.
     *
     * @param sessionId Unique session identifier
     * @param message Complete FIX message bytes
     * @param offset Offset in message buffer
     * @param length Length of message
     * @return true if written, false if buffer full
     */
    public boolean write(long sessionId, DirectBuffer message, int offset, int length) {
        int claimSize = HEADER_SIZE + length;
        int claimIndex = ringBuffer.tryClaim(MSG_TYPE_ID, claimSize);
        if (claimIndex < 0) {
            return false; // Buffer full
        }

        claimBuffer.wrap(ringBuffer.buffer(), claimIndex, claimSize);
        claimBuffer.putLong(0, sessionId);
        claimBuffer.putLong(8, 0L); // reserved
        claimBuffer.putBytes(HEADER_SIZE, message, offset, length);

        ringBuffer.commit(claimIndex);
        return true;
    }

    /**
     * Reads messages from ring buffer.
     * Called by worker thread.
     */
    public int read(IncomingMessageHandler handler, int limit) {
        return ringBuffer.read((msgTypeId, buffer, index, length) -> {
            long sessionId = buffer.getLong(index);
            int messageOffset = index + HEADER_SIZE;
            int messageLength = length - HEADER_SIZE;
            handler.onMessage(sessionId, buffer, messageOffset, messageLength);
        }, limit);
    }

    public int size() {
        return ringBuffer.size();
    }
}

@FunctionalInterface
public interface IncomingMessageHandler {
    void onMessage(long sessionId, DirectBuffer buffer, int offset, int length);
}
```

#### 2. `IncomingMessageWorker.java` (fix-engine)

```java
public class IncomingMessageWorker implements Runnable, AutoCloseable {
    private final IncomingRingBuffer ringBuffer;
    private final LongHashMap<FixSession> sessionMap; // sessionId -> FixSession
    private final ThreadLocal<IncomingFixMessage> messageWrapper;
    private volatile boolean running = true;

    public IncomingMessageWorker(IncomingRingBuffer ringBuffer) {
        this.ringBuffer = ringBuffer;
        this.sessionMap = new LongHashMap<>();
        this.messageWrapper = ThreadLocal.withInitial(() ->
            new IncomingFixMessage(1000)); // maxTagNumber
    }

    public void registerSession(long sessionId, FixSession session) {
        sessionMap.put(sessionId, session);
    }

    public void unregisterSession(long sessionId) {
        sessionMap.remove(sessionId);
    }

    @Override
    public void run() {
        final IdleStrategy idleStrategy = new BusySpinIdleStrategy();

        while (running) {
            int messagesRead = ringBuffer.read(this::handleMessage, 100);
            idleStrategy.idle(messagesRead);
        }
    }

    private void handleMessage(long sessionId, DirectBuffer buffer, int offset, int length) {
        FixSession session = sessionMap.get(sessionId);
        if (session == null) {
            return; // Session closed, discard message
        }

        IncomingFixMessage message = messageWrapper.get();
        message.wrap(buffer, offset, length);

        try {
            session.dispatchToListeners(message);
        } finally {
            message.reset();
        }
    }

    @Override
    public void close() {
        running = false;
    }
}
```

### Modified Classes

#### 1. `FixSession.java` Changes

```java
public class FixSession {
    private final IncomingRingBuffer incomingRingBuffer; // Shared, from FixEngine
    private final long sessionId; // Unique identifier

    // Called by event loop thread after parsing complete message
    private void queueForProcessing(IncomingFixMessage message) {
        // Copy message bytes to ring buffer
        boolean written = incomingRingBuffer.write(
            sessionId,
            message.getBuffer(),
            message.getOffset(),
            message.getLength()
        );

        if (!written) {
            // Backpressure - ring buffer full
            handleBackpressure(message);
        }
    }

    // Called by worker thread (public for worker access)
    public void dispatchToListeners(IncomingFixMessage message) {
        // Existing listener notification logic
        notifyListeners(message);
    }

    private void handleBackpressure(IncomingFixMessage message) {
        // Options:
        // 1. Block until space available (not recommended)
        // 2. Drop message and request resend later
        // 3. Process synchronously on event loop (fallback)
        log.warn("[{}] Ring buffer full, processing synchronously", sessionId);
        dispatchToListeners(message);
    }
}
```

#### 2. `FixEngine.java` Changes

```java
public class FixEngine {
    private final IncomingRingBuffer incomingRingBuffer;
    private final IncomingMessageWorker worker;
    private final Thread workerThread;

    public FixEngine(FixEngineConfig config) {
        // Create shared ring buffer (e.g., 4MB)
        this.incomingRingBuffer = new IncomingRingBuffer(4 * 1024 * 1024);

        // Create and start worker
        this.worker = new IncomingMessageWorker(incomingRingBuffer);
        this.workerThread = new Thread(worker, "fix-message-worker");
        workerThread.start();
    }

    public FixSession createSession(SessionConfig config) {
        FixSession session = new FixSession(config, incomingRingBuffer);
        worker.registerSession(session.getSessionId(), session);
        return session;
    }
}
```

#### 3. `IncomingFixMessage.java` Changes

Add method to wrap DirectBuffer:

```java
public void wrap(DirectBuffer buffer, int offset, int length) {
    reset();
    // Copy to internal byte array or wrap directly
    if (this.rawData == null || this.rawData.length < length) {
        this.rawData = new byte[length];
    }
    buffer.getBytes(offset, this.rawData, 0, length);
    this.buffer.wrap(this.rawData, 0, length);
    this.offset = 0;
    this.length = length;
    parseFields();
}
```

Note: This requires a copy because ring buffer memory is reclaimed after read callback. Alternative: use `controlledRead()` for explicit lifecycle management.

### Alternative: Controlled Read for True Zero-Copy

```java
public class IncomingMessageWorker {
    @Override
    public void run() {
        while (running) {
            ringBuffer.controlledRead((msgTypeId, buffer, index, length) -> {
                long sessionId = buffer.getLong(index);
                int messageOffset = index + HEADER_SIZE;
                int messageLength = length - HEADER_SIZE;

                FixSession session = sessionMap.get(sessionId);
                if (session == null) {
                    return ControlledMessageHandler.Action.CONTINUE;
                }

                // Wrap directly - valid only during this callback
                IncomingFixMessage message = messageWrapper.get();
                message.wrapDirect(buffer, messageOffset, messageLength);

                try {
                    session.dispatchToListeners(message);
                    return ControlledMessageHandler.Action.CONTINUE;
                } catch (Exception e) {
                    return ControlledMessageHandler.Action.BREAK;
                }
            });
        }
    }
}
```

This requires IncomingFixMessage to work with DirectBuffer (Agrona) instead of ByteBuffer.

## Configuration Options

| Option | Default | Description |
|--------|---------|-------------|
| `incomingRingBufferCapacity` | 4MB | Size of incoming ring buffer (power of 2) |
| `workerIdleStrategy` | BusySpinIdleStrategy | How worker waits for messages |
| `backpressureMode` | SYNC_FALLBACK | What to do when buffer full |

**Backpressure Modes:**
- `SYNC_FALLBACK`: Process on event loop thread (current behavior)
- `BLOCK`: Block event loop until space available (not recommended)
- `DROP_AND_RESEND`: Drop message, trigger resend request later

## Thread Safety Analysis

| Component | Producer Thread | Consumer Thread | Synchronization |
|-----------|-----------------|-----------------|-----------------|
| IncomingRingBuffer | NetworkEventLoop | Worker | Lock-free (Agrona) |
| sessionMap | Main (register) | Worker (lookup) | ConcurrentHashMap or volatile |
| IncomingFixMessage | Worker (wrap) | Worker (read) | ThreadLocal per worker |

## Implementation Steps

1. **Create `IncomingRingBuffer`** class in fix-network-io
2. **Create `IncomingMessageHandler`** interface
3. **Create `IncomingMessageWorker`** class in fix-engine
4. **Modify `IncomingFixMessage`** to support DirectBuffer wrapping
5. **Modify `FixSession`** to queue messages to ring buffer
6. **Modify `FixEngine`** to manage worker thread and ring buffer
7. **Add configuration** options for ring buffer capacity and idle strategy
8. **Update tests** to verify multi-threaded message processing
9. **Run benchmarks** to measure latency impact

## Performance Considerations

### Latency Impact

**Added latency:**
- Copy to ring buffer: ~100-200ns for 500-byte message
- Cross-thread handoff: ~200-500ns (cache line transfers)
- Total added: ~300-700ns

**Reduced latency:**
- Event loop no longer blocked by listener processing
- Can read next message immediately after queueing

### Throughput Impact

**Bottleneck analysis:**
- Event loop: Socket read + parse + copy to ring buffer
- Worker: Wrap message + dispatch to listeners

If listener processing is heavy (e.g., order matching, database writes), moving it off the event loop significantly improves throughput.

### Memory Impact

- Ring buffer: 4MB default (configurable)
- Per-message overhead: 16 bytes header + Agrona record header (8 bytes)

## Open Questions

1. **Should admin messages (Heartbeat, Logon, etc.) also go through ring buffer?**
   - Pro: Consistent processing path
   - Con: Adds latency for time-sensitive messages

2. **Should there be multiple worker threads?**
   - OneToOneRingBuffer is single-consumer
   - Could use ManyToManyRingBuffer or per-worker ring buffers

3. **How to handle session close while messages in flight?**
   - Worker may process message for closed session
   - Need graceful handling (discard with log)

4. **Should IncomingFixMessage changes be made to support DirectBuffer natively?**
   - Would enable true zero-copy in worker
   - Requires significant refactoring of field access methods

## Conclusion

The recommended approach is **Option A** (copy complete messages to ring buffer) because:
1. Minimal changes to existing, tested parsing logic
2. Clean ownership semantics
3. Copy overhead is small for typical FIX messages
4. Can be optimized later if needed

The main benefit is moving listener processing off the event loop thread, allowing higher throughput when listeners perform non-trivial work.
