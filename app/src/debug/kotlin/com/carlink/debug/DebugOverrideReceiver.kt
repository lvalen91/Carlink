package com.carlink.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.navigation.ClusterIconDelivery

/**
 * DEBUG-BUILD-ONLY receiver (lives in the `src/debug` source set, declared in
 * `src/debug/AndroidManifest.xml`) that writes boolean runtime overrides into the
 * [ClusterIconDelivery.DEBUG_OVERRIDES_PREFS] SharedPreferences from adb, so the coordinator
 * can A/B a switch on the GM VM or the radio without rebuilding.
 *
 * Usage (component-explicit broadcast; works on any API with the debug APK installed):
 *
 *   # force the stable content-URI cluster icons ON (also bypasses the gminfo37 device check)
 *   adb shell am broadcast -n zeno.carlink/com.carlink.debug.DebugOverrideReceiver \
 *       -a zeno.carlink.DEBUG_OVERRIDE --es key cluster_stable_uri_icons --ez value true
 *
 *   # force the pre-rev-156 bitmap path
 *   ... --es key cluster_stable_uri_icons --ez value false
 *
 *   # remove the override (back to the build default + platform gate)
 *   ... --es key cluster_stable_uri_icons
 *
 *   # just print the current state
 *   adb shell am broadcast -n zeno.carlink/com.carlink.debug.DebugOverrideReceiver -a zeno.carlink.DEBUG_OVERRIDE
 *
 * The receiver runs in the app process, so the in-memory SharedPreferences instance
 * ClusterIconDelivery reads sees the change immediately (no restart). Result is logged under
 * the NAVI tag as "[DEBUG_OVERRIDE] ...".
 */
class DebugOverrideReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val prefs = context.getSharedPreferences(ClusterIconDelivery.DEBUG_OVERRIDES_PREFS, Context.MODE_PRIVATE)
        val key = intent.getStringExtra(EXTRA_KEY)
        val action =
            when {
                key == null -> "query"
                intent.hasExtra(EXTRA_VALUE) -> {
                    val value = intent.getBooleanExtra(EXTRA_VALUE, false)
                    prefs.edit().putBoolean(key, value).apply()
                    "set $key=$value"
                }
                else -> {
                    prefs.edit().remove(key).apply()
                    "cleared $key"
                }
            }
        logInfo(
            "[DEBUG_OVERRIDE] $action; prefs=${prefs.all}; cluster icon delivery: ${ClusterIconDelivery.describe()}",
            tag = Logger.Tags.NAVI,
        )
    }

    private companion object {
        const val EXTRA_KEY = "key"
        const val EXTRA_VALUE = "value"
    }
}
