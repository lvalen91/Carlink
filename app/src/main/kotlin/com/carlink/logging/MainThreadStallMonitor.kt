package com.carlink.logging

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Observe foreground stalls without doing stack collection or disk I/O on main. */
internal class MainThreadStallMonitor(private val record: (String) -> Unit) : Application.ActivityLifecycleCallbacks {
    private val foreground = AtomicInteger()
    fun start(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val handler = Handler(Looper.getMainLooper())
            while (true) {
                Thread.sleep(3000)
                if (foreground.get() <= 0) continue
                val response = CountDownLatch(1)
                val probe = Runnable { response.countDown() }
                val started = System.nanoTime()
                handler.post(probe)
                if (!response.await(1500, TimeUnit.MILLISECONDS)) {
                    val stacks = Thread.getAllStackTraces().filterKeys {
                        it === Looper.getMainLooper().thread || it.name.startsWith("CL-") || it.name.startsWith("DefaultDispatcher")
                    }.entries.joinToString("\n") { (thread, stack) ->
                        "thread=${thread.name} state=${thread.state}\n${stack.take(32).joinToString("\n")}"
                    }
                    runCatching { record("[MAIN_STALL] foreground=true elapsedMs=${(System.nanoTime() - started) / 1_000_000}\n$stacks\n") }
                    handler.removeCallbacks(probe)
                }
            }
        }, "CL-MainStallMonitor").apply { isDaemon = true; start() }
    }
    override fun onActivityStarted(activity: Activity) { foreground.incrementAndGet() }
    override fun onActivityStopped(activity: Activity) { foreground.decrementAndGet() }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
