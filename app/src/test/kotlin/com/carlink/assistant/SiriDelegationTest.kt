package com.carlink.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class SiriDelegationTest {
    @Test fun disconnectedDoesNotStartAnything() {
        assertEquals(SiriRequestResult.NOT_CONNECTED, SiriDelegationRegistry { 0 }.request())
    }

    @Test fun acceptedRequestsAreDebouncedButFailuresCanRetry() {
        var now = 0L
        var calls = 0
        var result = SiriRequestResult.NOT_CONNECTED
        val target = SiriRequestTarget { calls++; result }
        val registry = SiriDelegationRegistry { now }
        registry.attach(target)
        assertEquals(SiriRequestResult.NOT_CONNECTED, registry.request())
        result = SiriRequestResult.QUEUED
        assertEquals(SiriRequestResult.QUEUED, registry.request())
        now = 1499
        assertEquals(SiriRequestResult.DUPLICATE, registry.request())
        assertEquals(2, calls)
        now = 1500
        assertEquals(SiriRequestResult.QUEUED, registry.request())
    }

    @Test fun oldManagerTeardownCannotDetachReplacement() {
        val old = SiriRequestTarget { SiriRequestResult.QUEUED }
        val replacement = SiriRequestTarget { SiriRequestResult.BUSY }
        val registry = SiriDelegationRegistry { 0 }
        registry.attach(old)
        registry.request()
        registry.attach(replacement)
        registry.detach(old)
        assertEquals(SiriRequestResult.BUSY, registry.request())
        registry.detach(replacement)
        assertEquals(SiriRequestResult.NOT_CONNECTED, registry.request())
    }

    @Test fun surfaceReattachmentDoesNotResetDebounce() {
        val target = SiriRequestTarget { SiriRequestResult.QUEUED }
        val registry = SiriDelegationRegistry { 0 }
        registry.attach(target)
        registry.request()
        registry.attach(target)
        assertEquals(SiriRequestResult.DUPLICATE, registry.request())
    }
}
