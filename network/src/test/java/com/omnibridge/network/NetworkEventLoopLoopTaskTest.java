package com.omnibridge.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The loop task runs once per iteration on the loop thread, in both loop modes, and stops
 * running once removed.
 */
class NetworkEventLoopLoopTaskTest {

    private NetworkEventLoop loop;

    @AfterEach
    void stop() {
        if (loop != null) {
            loop.stop();
        }
    }

    @ParameterizedTest(name = "busySpin={0}")
    @ValueSource(booleans = {true, false})
    void taskRunsEveryIterationOnTheLoopThreadUntilRemoved(boolean busySpin) throws Exception {
        loop = new NetworkEventLoop("loop-task-test", -1, 4096, 4096, 1, busySpin);
        loop.start();

        AtomicInteger ticks = new AtomicInteger();
        AtomicReference<Boolean> onLoop = new AtomicReference<>();
        CountDownLatch hundred = new CountDownLatch(100);
        loop.setLoopTask(() -> {
            onLoop.compareAndSet(null, loop.isInEventLoop());
            ticks.incrementAndGet();
            hundred.countDown();
        });

        assertTrue(hundred.await(5, TimeUnit.SECONDS), "task ticked 100 times");
        assertEquals(Boolean.TRUE, onLoop.get(), "task ran on the loop thread");

        loop.setLoopTask(null);
        // A tick already running when the task was removed may still land: settle, then watch
        Thread.sleep(50);
        int after = ticks.get();
        Thread.sleep(100);
        assertEquals(after, ticks.get(), "no ticks after removal");
    }

    @ParameterizedTest(name = "busySpin={0}")
    @ValueSource(booleans = {true, false})
    void aFailingTaskDoesNotStopTheLoop(boolean busySpin) throws Exception {
        loop = new NetworkEventLoop("loop-task-test", -1, 4096, 4096, 1, busySpin);
        loop.start();

        AtomicInteger ticks = new AtomicInteger();
        CountDownLatch ten = new CountDownLatch(10);
        loop.setLoopTask(() -> {
            ticks.incrementAndGet();
            ten.countDown();
            throw new IllegalStateException("tick " + ticks.get());
        });

        assertTrue(ten.await(5, TimeUnit.SECONDS), "loop kept iterating through the failures");
        assertTrue(loop.isRunning());
    }
}
