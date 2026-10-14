package com.carlink.audio

/** Diagnostics only: silence must never be used to discard live media samples. */
internal object PcmSamples {
    fun isSilent(data: ByteArray, offset: Int, length: Int): Boolean {
        if (length <= 0) return false
        for (index in offset until offset + length) {
            if (data[index] != 0.toByte()) return false
        }
        return true
    }
}
