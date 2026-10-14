package com.carlink.logging

/** Coordinates independent diagnostic writers with explicit user purges. */
internal object DiagnosticStorage {
    @Volatile private var generation = 0L
    fun generation(): Long = generation
    @Synchronized fun writeIfCurrent(token: Long, action: () -> Unit) {
        if (token == generation) action()
    }
    @Synchronized fun <T> purge(action: () -> T): T {
        generation++
        return action()
    }
}
