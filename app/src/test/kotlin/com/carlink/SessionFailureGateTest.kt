package com.carlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class SessionFailureGateTest {
    @Test fun duplicateAndStaleFailuresCannotClaimCleanup() {
        val gate = SessionFailureGate()
        val old = gate.begin()
        assertTrue(gate.claim(old))
        assertFalse(gate.claim(old))
        assertFalse(gate.isActive(old))
        val fresh = gate.begin()
        assertFalse(gate.claim(old))
        assertTrue(gate.isActive(fresh))
        assertTrue(gate.claim(fresh))
    }

    @Test fun simultaneousFailuresHaveOnlyOneCleanupOwner() {
        val gate = SessionFailureGate()
        val token = gate.begin()
        val start = CountDownLatch(1)
        val claimed = AtomicInteger()
        val threads = List(16) { Thread {
            start.await()
            if (gate.claim(token)) claimed.incrementAndGet()
        }.apply { start() } }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, claimed.get())
    }
}
