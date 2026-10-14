package com.carlink

/** Identifies sessions and permits at most one failure teardown per session. */
internal class SessionFailureGate {
    private var generation = 0L
    private var failed = false
    @Synchronized fun begin(): Long { failed = false; return ++generation }
    @Synchronized fun current(): Long = generation
    @Synchronized fun isActive(token: Long): Boolean = token == generation && !failed
    @Synchronized fun claim(token: Long): Boolean {
        if (token != generation || failed) return false
        failed = true
        return true
    }
}
