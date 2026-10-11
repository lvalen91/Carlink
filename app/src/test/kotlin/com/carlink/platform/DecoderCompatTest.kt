package com.carlink.platform

import com.carlink.platform.DecoderCompat.Probe
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the shrink search with an injected probe; no MediaCodec involved.
 * Robolectric runner only so the project Logger's android.util.Log calls resolve.
 */
@RunWith(RobolectricTestRunner::class)
class DecoderCompatTest {
    private var probes = 0
    private var now = 0L

    @Before
    fun reset() {
        DecoderCompat.clearCache()
        probes = 0
        now = 0L
    }

    /** Models msm_vidc: accept iff macroblocks per frame <= [maxMbpf]. */
    private fun mbpfProbe(maxMbpf: Int): (Int, Int) -> Probe =
        { w, h ->
            probes++
            if (((w + 15) / 16) * ((h + 15) / 16) <= maxMbpf) Probe.ACCEPTED else Probe.REJECTED
        }

    private fun run(
        w: Int,
        h: Int,
        probe: (Int, Int) -> Probe,
        deadlineMs: Long = 500L,
        key: String = "test:${w}x$h",
    ) = DecoderCompat.adjust(w, h, key, deadlineMs, { now }, probe)

    @Test
    fun nativeSizeAcceptedIsIdentityWithOneProbe() {
        assertEquals(2400 to 960, run(2400, 960, mbpfProbe(34816)))
        assertEquals(1, probes)
    }

    @Test
    fun tabletA9PlusShrinksToLargestFittingSize() {
        // 8160-macroblock driver limit; 1824x1136 = 114x71 = 8094 fits, 1840x1136 = 8165 does not.
        assertEquals(1824 to 1136, run(1920, 1200, mbpfProbe(8160)))
        assert(probes <= 8) { "expected <= 8 probes, got $probes" }
    }

    @Test
    fun portraitInputShrinksAlongHeight() {
        assertEquals(1136 to 1824, run(1200, 1920, mbpfProbe(8160)))
    }

    @Test
    fun deepLimitIsStillFoundWithinProbeBudget() {
        // 720p-class decoder (3600 MB). Linear 16 px stepping would need ~45 steps.
        val (w, h) = run(1920, 1200, mbpfProbe(3600))
        assert(((w + 15) / 16) * ((h + 15) / 16) <= 3600) { "${w}x$h exceeds limit" }
        assert(w >= 1200) { "search stopped too low at ${w}x$h" } // 1216x768 = 3648 MB, over
        assert(probes <= 8) { "expected <= 8 probes, got $probes" }
    }

    @Test
    fun alwaysRejectedReturnsInputUncached() {
        val alwaysReject: (Int, Int) -> Probe = { _, _ -> probes++; Probe.REJECTED }
        assertEquals(1920 to 1200, run(1920, 1200, alwaysReject))
        val first = probes
        assertEquals(1920 to 1200, run(1920, 1200, alwaysReject))
        assert(probes > first) { "failure outcome must not be cached" }
    }

    @Test
    fun abortReturnsInputUncached() {
        val busy: (Int, Int) -> Probe = { _, _ -> probes++; Probe.ABORT }
        assertEquals(1920 to 1200, run(1920, 1200, busy))
        assertEquals(1, probes)
        run(1920, 1200, busy)
        assertEquals(2, probes)
    }

    @Test
    fun abortMidSearchReturnsInput() {
        var calls = 0
        val flaky: (Int, Int) -> Probe = { w, h ->
            calls++
            if (calls == 3) Probe.ABORT else mbpfProbe(8160)(w, h)
        }
        assertEquals(1920 to 1200, run(1920, 1200, flaky))
    }

    @Test
    fun deadlineExpiryReturnsInputUncached() {
        val slow: (Int, Int) -> Probe = { w, h ->
            now += 300
            mbpfProbe(8160)(w, h)
        }
        assertEquals(1920 to 1200, run(1920, 1200, slow, deadlineMs = 500L))
        assert(probes <= 2) { "expected the deadline to stop probing, got $probes" }
    }

    @Test
    fun successIsCached() {
        assertEquals(1824 to 1136, run(1920, 1200, mbpfProbe(8160)))
        val first = probes
        assertEquals(1824 to 1136, run(1920, 1200, mbpfProbe(8160)))
        assertEquals(first, probes)
    }

    @Test
    fun cacheIsPerCodecName() {
        run(1920, 1200, mbpfProbe(8160), key = "a:1920x1200")
        val first = probes
        run(1920, 1200, mbpfProbe(8160), key = "b:1920x1200")
        assert(probes > first)
    }

    @Test
    fun zeroInputIsReturnedWithoutProbing() {
        assertEquals(0 to 1080, run(0, 1080, mbpfProbe(8160)))
        assertEquals(0, probes)
    }

    @Test
    fun tinyInputBelowMinimumIsReturnedUnchanged() {
        assertEquals(300 to 200, run(300, 200, { _, _ -> probes++; Probe.REJECTED }))
        assertEquals(1, probes)
    }
}
