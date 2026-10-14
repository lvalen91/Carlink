package com.carlink.protocol

import com.carlink.usb.UsbTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class AdapterDriverLifecycleTest {
    private class Transport : UsbTransport {
        override val isOpened = true
        var writes = 0
        var readers = 0
        var onWrite: (ByteArray) -> Int = { it.size }
        override fun write(data: ByteArray, timeout: Int): Int {
            writes++
            return onWrite(data)
        }
        override fun startReadingLoop(callback: UsbTransport.ReadingLoopCallback, timeout: Int, videoProcessor: UsbTransport.VideoDataProcessor?) {
            readers++
        }
        override fun stopReadingLoop() = Unit
        override fun close() = Unit
    }

    @Test
    fun stopDuringInitializationDoesNotStartReaderOrWifiTimer() {
        val transport = Transport()
        val logs = mutableListOf<String>()
        val driver = AdapterDriver(transport, {}, {}, { logs.add(it) })
        transport.onWrite = { driver.stop(); it.size }
        try {
            assertFalse(driver.start(initMode = "MINIMAL_ONLY"))
            Thread.sleep(750)
            assertEquals(1, transport.writes)
            assertEquals(0, transport.readers)
            assertFalse(logs.any { it.contains("Initialization sequence completed") })
        } finally {
            driver.stop()
        }
    }

    @Test
    fun failedInitializationWriteAbortsWithoutStartingReader() {
        val transport = Transport()
        transport.onWrite = { -1 }
        val driver = AdapterDriver(transport, {}, {}, {})
        try {
            assertFalse(driver.start(initMode = "MINIMAL_ONLY"))
            assertEquals(1, transport.writes)
            assertEquals(0, transport.readers)
        } finally {
            driver.stop()
        }
    }
}
