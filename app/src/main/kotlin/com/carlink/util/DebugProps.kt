package com.carlink.util

import com.carlink.BuildConfig

/**
 * Debug-build-only read of `debug.*` Android system properties, for visual test hooks that
 * need to be flipped from `adb shell setprop` without a rebuild (e.g. rendering synthetic
 * paired devices on an emulator that has no adapter attached).
 *
 * Every accessor short-circuits on `BuildConfig.DEBUG`, which is a compile-time constant in
 * release builds, so R8 strips the reflection and the hooks' callers' branches entirely.
 * `android.os.SystemProperties` is reached by reflection because it is a hidden API;
 * `get(String)` is on the greylist and works on every API level this app targets. Any
 * failure returns null — a debug hook must never be able to crash the app.
 */
object DebugProps {
    fun get(name: String): String? {
        if (!BuildConfig.DEBUG) return null
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val value = clazz.getMethod("get", String::class.java).invoke(null, name) as? String
            value?.takeIf { it.isNotBlank() }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            null
        }
    }

    fun isSet(name: String): Boolean = get(name)?.let { it != "0" && !it.equals("false", ignoreCase = true) } ?: false
}
