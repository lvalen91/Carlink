package com.carlink.assistant

import java.lang.ref.WeakReference

enum class SiriRequestResult(val feedback: String) {
    QUEUED("Siri request sent"),
    NOT_CONNECTED("Connect CarPlay to use Siri"),
    BUSY("Voice session already active"),
    DUPLICATE("Siri already requested"),
    UNAVAILABLE("Siri command unavailable"),
}

fun interface SiriRequestTarget {
    /** Must return promptly; USB work belongs on the target's command worker. */
    fun requestSiri(): SiriRequestResult
}

/** Process-local bridge: never retains an Activity or starts a projection session. */
internal class SiriDelegationRegistry(private val nowMs: () -> Long) {
    private var target = WeakReference<SiriRequestTarget>(null)
    private var lastAcceptedMs: Long? = null

    @Synchronized fun attach(value: SiriRequestTarget) {
        if (target.get() !== value) {
            target = WeakReference(value)
            lastAcceptedMs = null
        }
    }

    @Synchronized fun detach(value: SiriRequestTarget) {
        // Teardown of an older manager must not detach its replacement.
        if (target.get() === value) {
            target.clear()
            lastAcceptedMs = null
        }
    }

    @Synchronized fun request(): SiriRequestResult {
        val current = target.get() ?: return SiriRequestResult.NOT_CONNECTED
        val now = nowMs()
        if (lastAcceptedMs?.let { now - it < 1500L } == true) return SiriRequestResult.DUPLICATE
        val result = current.requestSiri()
        if (result == SiriRequestResult.QUEUED) lastAcceptedMs = now
        return result
    }
}

internal object SiriDelegation {
    val registry = SiriDelegationRegistry { android.os.SystemClock.elapsedRealtime() }
}
