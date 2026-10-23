package com.omnibridge.apps.fix.initiator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RatePacerTest {

    @Test
    void scheduleDoesNotDrift() {
        RatePacer pacer = new RatePacer(1_000, 1_000);
        assertTrue(pacer.isPaced());
        assertEquals(1_000, pacer.intendedSendTime(0));
        assertEquals(1_000 + 1_000_000, pacer.intendedSendTime(1));
        assertEquals(1_000 + 99_999L * 1_000_000, pacer.intendedSendTime(99_999));
    }

    @Test
    void waitsUntilDueAndReturnsTheIntendedTime() {
        long start = System.nanoTime();
        RatePacer pacer = new RatePacer(start, 10_000); // 100 us apart
        for (int i = 0; i < 20; i++) {
            long due = pacer.awaitSendTime(i);
            assertEquals(start + i * 100_000L, due);
            assertTrue(System.nanoTime() - due >= 0, "returned before order " + i + " was due");
        }
    }

    @Test
    void lateOrdersAreNotRescheduled() {
        long start = System.nanoTime() - 1_000_000_000L; // a second behind
        RatePacer pacer = new RatePacer(start, 1_000);
        // Already due: returns at once with the original slot
        assertEquals(start + 5_000_000, pacer.awaitSendTime(5));
    }

    @Test
    void unpacedSendsAtOnce() {
        RatePacer pacer = new RatePacer(0, 0);
        assertFalse(pacer.isPaced());
        long before = System.nanoTime();
        long t = pacer.awaitSendTime(42);
        assertTrue(t - before >= 0);
    }
}
