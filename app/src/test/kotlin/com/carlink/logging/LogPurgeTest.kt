package com.carlink.logging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class LogPurgeTest {
    @Test fun enabledPurgeRemovesDiagnosticsAndStartsFreshLog() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = FileLogManager(context)
        try {
            manager.enable()
            val dir = File(context.filesDir, "logs")
            val diagnostic = File(dir, "carlink-main-stalls.log").apply { writeText("old evidence") }
            val unrelated = File(context.filesDir, "keep-settings.txt").apply { writeText("keep") }
            File(context.cacheDir, "carlink-diagnostics.zip").writeText("old archive")
            val result = manager.purgeAllLogs()
            assertTrue(result.deleted >= 2)
            assertTrue(result.failed.isEmpty())
            assertFalse(diagnostic.exists())
            assertFalse(File(context.cacheDir, "carlink-diagnostics.zip").exists())
            assertEquals("keep", unrelated.readText())
            Logger.i("fresh event", "PURGE_TEST")
            manager.flush()
            assertTrue(manager.getLogFiles().any { it.readText().contains("fresh event") })
            assertFalse(manager.getLogFiles().any { it.readText().contains("old evidence") })
        } finally { manager.release() }
    }

    @Test fun disabledPurgeDoesNotEnableLoggingAndRejectsOldDiagnosticWrites() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = FileLogManager(context)
        try {
            File(context.filesDir, "logs/old.log").apply { parentFile!!.mkdirs(); writeText("old") }
            val oldGeneration = DiagnosticStorage.generation()
            manager.purgeAllLogs()
            var wroteOld = false
            DiagnosticStorage.writeIfCurrent(oldGeneration) { wroteOld = true }
            assertFalse(wroteOld)
            assertTrue(manager.getLogFiles().isEmpty())
        } finally { manager.release() }
    }
}
