package com.carlink.navigation

import android.content.Context
import android.content.SharedPreferences
import com.carlink.BuildConfig
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.platform.PlatformDetector

/**
 * Single switch for HOW maneuver icons are handed to Templates Host for the cluster
 * (rev 156, EXPERIMENTAL — pending validation on the GM VM and then the truck):
 *
 *  - stable content-URI CarIcons (`IconCompat.createWithContentUri`, served by
 *    [com.carlink.provider.ClusterIconShimProvider] from
 *    [com.carlink.provider.StableIconRegistry]) — the host hashes these by content and caches
 *    them, so the per-second PNG re-encode and GM cluster re-upload stop; or
 *  - the pre-rev-156 bitmap CarIcons (`IconCompat.createWithBitmap`).
 *
 * Decision = platform gate AND switch:
 *
 *  Platform gate (where the cluster_icon_investigation says it helps):
 *   - this build/install must OWN the GM Templates Host cluster-icon authority
 *     ([NavigationStateManager.isClusterIconAuthorityOwned]: the sideload flavor and the
 *     owner's play build; a fork's play build resolves the GM literal to nothing and keeps
 *     its current text-only behavior — see rev 128 and the probe comment in
 *     NavigationStateManager, which is intentionally NOT templated);
 *   - NOT the GM VCU/VCUNH1 enum-only cluster (rev 141: bitmaps/URIs are masked there);
 *   - the device is gminfo37 ([PlatformDetector.PlatformInfo.isGmInfo37]). The AAOS
 *     emulator and every other platform keep the bitmap path unless the debug override
 *     below says otherwise.
 *
 *  Switch: [STABLE_URI_ICONS_BUILD_DEFAULT] (compile-time), overridable at runtime in
 *  DEBUG builds only through SharedPreferences [DEBUG_OVERRIDES_PREFS] /
 *  [DEBUG_OVERRIDE_KEY_STABLE_URI_ICONS]: `true` forces the URI path on (and skips the
 *  gminfo37 device check so the realk VM / AAOS emulator can exercise it), `false` forces
 *  the bitmap path, absent = build default. Written by the debug-only
 *  `com.carlink.debug.DebugOverrideReceiver` (`adb shell am broadcast ...`, see its KDoc)
 *  or by editing the prefs XML with `run-as`. Read live on every Maneuver build, so a flip
 *  takes effect on the next Trip without a restart (CarPlay composed icons additionally
 *  need their PNG registered, which happens at compose time — a route loaded while the
 *  switch was OFF falls back to bitmaps for that route; AA icons are registered on arrival
 *  regardless, so they flip immediately).
 */
object ClusterIconDelivery {
    /** Build-time default for the stable content-URI icon path. Flip to false to ship bitmaps. */
    const val STABLE_URI_ICONS_BUILD_DEFAULT = true

    /** SharedPreferences file holding DEBUG-build runtime overrides. */
    const val DEBUG_OVERRIDES_PREFS = "carlink_debug_overrides"

    /** Boolean key in [DEBUG_OVERRIDES_PREFS]; absent = use [STABLE_URI_ICONS_BUILD_DEFAULT]. */
    const val DEBUG_OVERRIDE_KEY_STABLE_URI_ICONS = "cluster_stable_uri_icons"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var platformIsGmInfo37 = false

    @Volatile
    private var platformIsVcu = false

    /** Logged state of the last [useStableUriIcons] decision so flips show up once in the log. */
    @Volatile
    private var lastDecision: Boolean? = null

    /**
     * Called once from [com.carlink.CarlinkManager] init, AFTER [NavigationStateManager.initialize]
     * (the authority-ownership probe this gate reads).
     */
    fun initialize(
        context: Context,
        platformInfo: PlatformDetector.PlatformInfo,
    ) {
        appContext = context.applicationContext
        platformIsGmInfo37 = platformInfo.isGmInfo37()
        platformIsVcu = platformInfo.isVcuCluster()
        lastDecision = null
        logInfo("[ICON_DELIVERY] ${describe()}", tag = Logger.Tags.NAVI)
    }

    private fun debugPrefs(): SharedPreferences? {
        if (!BuildConfig.DEBUG) return null
        return appContext?.getSharedPreferences(DEBUG_OVERRIDES_PREFS, Context.MODE_PRIVATE)
    }

    /** DEBUG builds: the runtime override if one is set, else null. Always null in release. */
    fun debugOverride(): Boolean? {
        val prefs = debugPrefs() ?: return null
        return if (prefs.contains(DEBUG_OVERRIDE_KEY_STABLE_URI_ICONS)) {
            prefs.getBoolean(DEBUG_OVERRIDE_KEY_STABLE_URI_ICONS, STABLE_URI_ICONS_BUILD_DEFAULT)
        } else {
            null
        }
    }

    /**
     * True when maneuver icons should be published as stable content URIs. Cheap (volatile
     * reads + an in-memory SharedPreferences lookup in DEBUG); called per Maneuver build on
     * the cluster session's main-thread dispatch and once per compose job on the
     * IconComposer worker.
     */
    fun useStableUriIcons(): Boolean {
        val decision = computeDecision()
        if (decision != lastDecision) {
            lastDecision = decision
            logInfo("[ICON_DELIVERY] stable URI icons ${if (decision) "ON" else "OFF"} — ${describe()}", tag = Logger.Tags.NAVI)
        }
        return decision
    }

    private fun computeDecision(): Boolean {
        if (platformIsVcu) return false
        if (!NavigationStateManager.isClusterIconAuthorityOwned()) return false
        val override = debugOverride()
        if (override != null) return override
        return STABLE_URI_ICONS_BUILD_DEFAULT && platformIsGmInfo37
    }

    /** One-line state dump for logs. */
    fun describe(): String =
        "buildDefault=$STABLE_URI_ICONS_BUILD_DEFAULT debugOverride=${debugOverride()} " +
            "gminfo37=$platformIsGmInfo37 vcu=$platformIsVcu " +
            "authorityOwned=${NavigationStateManager.isClusterIconAuthorityOwned()}"
}
