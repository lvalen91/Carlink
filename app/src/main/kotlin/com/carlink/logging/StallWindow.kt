package com.carlink.logging

/** Bounded recent samples; clocks and thresholds are independent of Android APIs. */
class StallWindow {
    private val history = java.util.ArrayDeque<String>()
    private var previousProgress: Long? = null
    private var unchangedSince = 0L
    private var anomalySince: Long? = null
    private var lastReport: Long? = null

    @Synchronized
    fun sample(nowMs: Long, active: Boolean, progress: Long, detail: String, anomaly: Boolean): String? {
        if (!active) {
            history.clear()
            previousProgress = null
            anomalySince = null
            lastReport = null
            return null
        }
        if (previousProgress != progress) unchangedSince = nowMs
        previousProgress = progress
        if (anomaly) {
            if (anomalySince == null) anomalySince = nowMs
        } else anomalySince = null
        history.addLast("t=$nowMs $detail")
        while (history.size > 8) history.removeFirst()
        val stoppedMs = nowMs - unchangedSince
        val abnormalMs = anomalySince?.let { nowMs - it } ?: 0L
        if (maxOf(stoppedMs, abnormalMs) < 2000 || lastReport?.let { nowMs - it < 10000 } == true) return null
        lastReport = nowMs
        return "progressStoppedMs=$stoppedMs anomalyMs=$abnormalMs\n${history.joinToString("\n")}"
    }
}
