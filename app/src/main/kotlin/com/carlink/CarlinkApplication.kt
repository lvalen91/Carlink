package com.carlink

import android.app.Application
import android.app.ActivityManager
import android.os.Build
import java.io.File
import java.time.Instant

/** Process-level evidence survives activity recreation and abrupt process exits. */
class CarlinkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.carlink.logging.MainThreadStallMonitor { text ->
            persist("carlink-main-stalls.log", "${Instant.now()} $text")
        }.start(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                persist("carlink-crashes.log", "${Instant.now()} [UNCAUGHT] thread=${thread.name}\n${error.stackTraceToString().take(32768)}\n")
            } catch (_: Throwable) {
                // Never replace the original crash with a diagnostic failure.
            } finally {
                if (previous != null) previous.uncaughtException(thread, error)
                else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            Thread({
                runCatching {
                    val prefs = getSharedPreferences("process_exit_diagnostics", MODE_PRIVATE)
                    val seen = prefs.getLong("lastTimestamp", 0)
                    val manager = getSystemService(ActivityManager::class.java)
                    val exits = manager.getHistoricalProcessExitReasons(packageName, 0, 10)
                        .filter { it.timestamp > seen }.sortedBy { it.timestamp }
                    for (exit in exits) {
                        val reasonName = when (exit.reason) {
                            android.app.ApplicationExitInfo.REASON_CRASH -> "java-crash"
                            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
                            android.app.ApplicationExitInfo.REASON_ANR -> "anr"
                            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
                            android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "user-requested"
                            android.app.ApplicationExitInfo.REASON_SIGNALED -> "signal"
                            else -> "other"
                        }
                        persist("carlink-process-exits.log", "${Instant.ofEpochMilli(exit.timestamp)} [PROCESS_EXIT] " +
                            "reason=${exit.reason}($reasonName) status=${exit.status} pid=${exit.pid} importance=${exit.importance} " +
                            "pssKb=${exit.pss} rssKb=${exit.rss} description=${exit.description}\n")
                        // Native exit traces can be binary protobuf, not readable text.
                        if (exit.reason == android.app.ApplicationExitInfo.REASON_ANR) exit.traceInputStream?.use { input ->
                            val bytes = ByteArray(32768)
                            var remaining = bytes.size
                            val trace = java.io.ByteArrayOutputStream()
                            while (remaining > 0) {
                                val count = input.read(bytes, 0, remaining)
                                if (count <= 0) break
                                trace.write(bytes, 0, count)
                                remaining -= count
                            }
                            persist("carlink-process-exits.log", trace.toString("UTF-8") + "\n")
                        }
                    }
                    exits.lastOrNull()?.let { prefs.edit().putLong("lastTimestamp", it.timestamp).apply() }
                }
            }, "CL-ExitDiagnostics").start()
        }
    }

    @Synchronized private fun persist(name: String, text: String) {
        val generation = com.carlink.logging.DiagnosticStorage.generation()
        com.carlink.logging.DiagnosticStorage.writeIfCurrent(generation) {
        val directory = File(filesDir, "logs").apply { mkdirs() }
        val file = File(directory, name)
        if (file.length() > 128 * 1024) file.writeText("")
        java.io.FileOutputStream(file, true).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        }
    }
}
