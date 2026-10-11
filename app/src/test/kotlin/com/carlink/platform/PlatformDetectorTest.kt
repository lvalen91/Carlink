package com.carlink.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The MediaCodecList scan behind [PlatformDetector.detect] must run once per process no
 * matter how many call sites ask, including concurrent first callers; a scan that throws
 * must not be cached.
 */
@RunWith(RobolectricTestRunner::class)
class PlatformDetectorTest {
    private val scans = AtomicInteger(0)
    private lateinit var context: Context
    private lateinit var realScanner: () -> String?

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        realScanner = PlatformDetector.hardwareH264DecoderScanner
        PlatformDetector.clearCacheForTesting()
        scans.set(0)
    }

    @After
    fun tearDown() {
        PlatformDetector.hardwareH264DecoderScanner = realScanner
        PlatformDetector.clearCacheForTesting()
    }

    @Test
    fun scanRunsOnceAcrossRepeatedCalls() {
        PlatformDetector.hardwareH264DecoderScanner = {
            scans.incrementAndGet()
            "OMX.test.avc.decoder"
        }
        repeat(8) {
            assertEquals("OMX.test.avc.decoder", PlatformDetector.detect(context).hardwareH264DecoderName)
        }
        assertEquals(1, scans.get())
    }

    @Test
    fun noDecoderResultIsCachedToo() {
        PlatformDetector.hardwareH264DecoderScanner = {
            scans.incrementAndGet()
            null
        }
        repeat(3) { assertNull(PlatformDetector.detect(context).hardwareH264DecoderName) }
        assertEquals(1, scans.get())
    }

    @Test
    fun concurrentFirstCallersShareOneScan() {
        val release = CountDownLatch(1)
        PlatformDetector.hardwareH264DecoderScanner = {
            scans.incrementAndGet()
            release.await(2, TimeUnit.SECONDS)
            "OMX.test.avc.decoder"
        }
        val pool = Executors.newFixedThreadPool(6)
        val results = (1..6).map { pool.submit<String?> { PlatformDetector.detect(context).hardwareH264DecoderName } }
        Thread.sleep(100) // let every worker reach the lock
        release.countDown()
        results.forEach { assertEquals("OMX.test.avc.decoder", it.get(2, TimeUnit.SECONDS)) }
        pool.shutdown()
        assertEquals(1, scans.get())
    }

    @Test
    fun failedScanIsRetried() {
        PlatformDetector.hardwareH264DecoderScanner = {
            if (scans.incrementAndGet() == 1) throw IllegalStateException("media.codec died")
            "OMX.test.avc.decoder"
        }
        assertNull(PlatformDetector.detect(context).hardwareH264DecoderName)
        assertEquals("OMX.test.avc.decoder", PlatformDetector.detect(context).hardwareH264DecoderName)
        assertEquals("OMX.test.avc.decoder", PlatformDetector.detect(context).hardwareH264DecoderName)
        assertEquals(2, scans.get())
    }
}
