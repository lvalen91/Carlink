package com.carlink.logging

import org.junit.Assert.*
import org.junit.Test

class StallWindowTest {
    @Test fun capturesRecentSamplesWhenProgressStops() {
        val window = StallWindow()
        assertNull(window.sample(0, true, 10, "first", false))
        assertNull(window.sample(1000, true, 10, "second", false))
        val report = window.sample(2000, true, 10, "third", false)!!
        assertTrue(report.contains("progressStoppedMs=2000"))
        assertTrue(report.contains("first"))
        assertTrue(report.contains("third"))
        assertNull(window.sample(3000, true, 10, "fourth", false))
    }

    @Test fun inactiveResetsAndHealthyProgressDoesNotReport() {
        val window = StallWindow()
        repeat(20) { assertNull(window.sample(it * 1000L, true, it.toLong(), "healthy", false)) }
        assertNull(window.sample(21000, false, 19, "background", true))
        assertNull(window.sample(100000, true, 19, "resume", false))
    }

    @Test fun detectsSilenceDespiteOutputProgressAndBoundsHistory() {
        val window = StallWindow()
        repeat(12) { window.sample(it * 1000L, true, it.toLong(), "sample-$it", true) }
        val report = window.sample(12000, true, 12, "sample-12", true)!!
        assertTrue(report.contains("anomalyMs=12000"))
        assertFalse(report.contains("sample-0\n"))
        assertEquals(9, report.lines().size)
    }
}
