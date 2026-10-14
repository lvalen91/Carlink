package com.carlink.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AudioRingBufferTest {
    @Test
    fun callBacklogTrimmingKeepsNewestSixtyMilliseconds() {
        for (sampleRate in listOf(8000, 16000)) {
            val bytesPerMs = sampleRate * 2 / 1000
            val buffer = AudioRingBuffer(120, sampleRate, 1)
            val samples = ByteArray(bytesPerMs * 110) { (it / 2).toByte() }
            buffer.write(samples)
            val discarded = buffer.discardOldest(buffer.availableForRead() - bytesPerMs * 60)
            assertEquals(bytesPerMs * 50, discarded)
            assertEquals(60, buffer.fillLevelMs())
            val remaining = ByteArray(bytesPerMs * 60)
            assertEquals(remaining.size, buffer.read(remaining))
            org.junit.Assert.assertArrayEquals(samples.copyOfRange(discarded, samples.size), remaining)
        }
    }

    @Test
    fun overflowAndConcurrentTrimmingPreserveStereoFrames() {
        val buffer = AudioRingBuffer(10, 48000, 2)
        val done = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val producer = Thread {
            try {
                repeat(20000) { index ->
                    val value = index.toByte()
                    buffer.write(byteArrayOf(value, value, value.inv(), value.inv()))
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                done.set(true)
            }
        }
        producer.start()
        val frame = ByteArray(4)
        while (!done.get() || buffer.availableForRead() > 0) {
            if (buffer.availableForRead() > 960) buffer.discardOldest(480)
            val count = buffer.read(frame)
            if (count == 0) continue
            assertEquals(4, count)
            assertEquals(frame[0], frame[1])
            assertEquals(frame[0].inv(), frame[2])
            assertEquals(frame[2], frame[3])
        }
        producer.join()
        failure.get()?.let { throw it }
    }

    private fun Byte.inv(): Byte = toInt().inv().toByte()
}
