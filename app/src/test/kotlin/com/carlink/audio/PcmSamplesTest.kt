package com.carlink.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmSamplesTest {
    @Test fun detectsNonzeroSampleBetweenOldProbePositions() {
        val packet = ByteArray(4096)
        packet[123] = 1
        assertFalse(PcmSamples.isSilent(packet, 0, packet.size))
    }

    @Test fun respectsPayloadOffsetAndLength() {
        val packet = byteArrayOf(1, 0, 0, 2)
        assertTrue(PcmSamples.isSilent(packet, 1, 2))
        assertFalse(PcmSamples.isSilent(packet, 0, 4))
        assertFalse(PcmSamples.isSilent(packet, 1, 0))
    }

    @Test fun silenceRetainsPlaybackDurationInMusicRing() {
        val ring = AudioRingBuffer(750, 48000, 2)
        val packet = ByteArray(48000 * 4 * 60 / 1000)
        assertTrue(PcmSamples.isSilent(packet, 0, packet.size))
        assertEquals(packet.size, ring.write(packet))
        assertEquals(60, ring.fillLevelMs())
        val output = ByteArray(packet.size)
        assertEquals(packet.size, ring.read(output))
        assertTrue(PcmSamples.isSilent(output, 0, output.size))
    }
}
