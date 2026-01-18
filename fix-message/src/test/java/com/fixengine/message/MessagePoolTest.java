package com.fixengine.message;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MessagePool.
 */
class MessagePoolTest {

    private MessagePoolConfig config;

    @BeforeEach
    void setUp() {
        config = MessagePoolConfig.builder()
                .poolSize(4)
                .maxMessageLength(1024)
                .maxTagNumber(500)
                .beginString("FIX.4.4")
                .senderCompId("SENDER")
                .targetCompId("TARGET")
                .build();
    }

    @Test
    void constructor_createsPoolWithCorrectSize() {
        MessagePool pool = new MessagePool(config);

        assertEquals(4, pool.getPoolSize());
        assertEquals(4, pool.availableCount());
        assertEquals(0, pool.inUseCount());
    }

    @Test
    void acquire_returnsMessage() throws InterruptedException {
        MessagePool pool = new MessagePool(config);

        OutgoingFixMessage msg = pool.acquire();

        assertNotNull(msg);
        assertTrue(msg.isInUse());
        assertEquals(3, pool.availableCount());
        assertEquals(1, pool.inUseCount());
    }

    @Test
    void acquire_exhaustsPool() throws InterruptedException {
        MessagePool pool = new MessagePool(config);
        List<OutgoingFixMessage> messages = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            messages.add(pool.acquire());
        }

        assertEquals(0, pool.availableCount());
        assertEquals(4, pool.inUseCount());
        assertTrue(pool.isEmpty());
    }

    @Test
    void tryAcquire_returnsNullWhenEmpty() throws InterruptedException {
        MessagePool pool = new MessagePool(config);

        // Exhaust the pool
        for (int i = 0; i < 4; i++) {
            pool.acquire();
        }

        OutgoingFixMessage msg = pool.tryAcquire();
        assertNull(msg);
    }

    @Test
    void tryAcquire_withTimeout_returnsNullOnTimeout() throws InterruptedException {
        MessagePool pool = new MessagePool(config);

        // Exhaust the pool
        for (int i = 0; i < 4; i++) {
            pool.acquire();
        }

        long start = System.currentTimeMillis();
        OutgoingFixMessage msg = pool.tryAcquire(100, TimeUnit.MILLISECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertNull(msg);
        assertTrue(elapsed >= 100);
    }

    @Test
    void release_returnsMessageToPool() throws InterruptedException {
        MessagePool pool = new MessagePool(config);

        OutgoingFixMessage msg = pool.acquire();
        assertEquals(3, pool.availableCount());

        pool.release(msg);
        assertEquals(4, pool.availableCount());
    }

    @Test
    void release_resetsMessage() throws InterruptedException {
        MessagePool pool = new MessagePool(config);

        OutgoingFixMessage msg = pool.acquire();
        msg.setMsgType("D");
        msg.setField(11, "ORDER-001");

        msg.release();  // This calls pool.release internally

        // Acquire should return a reset message
        OutgoingFixMessage msg2 = pool.acquire();
        assertNull(msg2.getMsgType());
        assertFalse(msg2.hasTag(11));
    }

    @Test
    void release_null_throwsException() {
        MessagePool pool = new MessagePool(config);

        assertThrows(IllegalArgumentException.class, () -> pool.release(null));
    }

    @Test
    void warmUp_touchesAllMessages() {
        MessagePool pool = new MessagePool(config);

        // This should not throw
        pool.warmUp();

        // All messages should still be available
        assertEquals(4, pool.availableCount());
    }

    @Test
    void concurrentAccess_safeUnderContention() throws InterruptedException {
        MessagePoolConfig largeConfig = MessagePoolConfig.builder()
                .poolSize(16)
                .maxMessageLength(1024)
                .maxTagNumber(500)
                .beginString("FIX.4.4")
                .senderCompId("SENDER")
                .targetCompId("TARGET")
                .build();
        MessagePool pool = new MessagePool(largeConfig);

        int numThreads = 8;
        int iterations = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < iterations; i++) {
                        OutgoingFixMessage msg = pool.tryAcquire(100, TimeUnit.MILLISECONDS);
                        if (msg != null) {
                            msg.setMsgType("D");
                            msg.setField(11, "ORDER-" + i);
                            Thread.yield();
                            msg.release();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        assertEquals(0, errors.get());

        // All messages should be back in pool
        assertEquals(16, pool.availableCount());
    }

    @Test
    void getConfig_returnsConfiguration() {
        MessagePool pool = new MessagePool(config);

        assertSame(config, pool.getConfig());
    }

    @Test
    void toString_containsPoolInfo() {
        MessagePool pool = new MessagePool(config);

        String str = pool.toString();

        assertTrue(str.contains("size=4"));
        assertTrue(str.contains("available=4"));
        assertTrue(str.contains("inUse=0"));
    }

    @Test
    void isEmpty_returnsCorrectly() throws InterruptedException {
        MessagePool pool = new MessagePool(config);

        assertFalse(pool.isEmpty());

        // Acquire all
        List<OutgoingFixMessage> messages = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            messages.add(pool.acquire());
        }

        assertTrue(pool.isEmpty());

        // Release one
        messages.get(0).release();
        assertFalse(pool.isEmpty());
    }
}
