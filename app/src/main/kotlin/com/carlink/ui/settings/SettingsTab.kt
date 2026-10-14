package com.carlink.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.carlink.BuildConfig

/**
 * Sections of the Settings experience and their build-flavor visibility.
 *
 * HISTORY: these were the tabs of a NavigationRail. The settings UI is now a single adaptive
 * dashboard (SettingsScreen.kt): PHONES and CONTROL are both part of the dashboard home
 * page, and LOGS is a secondary page reached from a dashboard button. The enum survives
 * solely as the owner of the [visible] build-gating rule — the dashboard asks
 * `SettingsTab.LOGS in SettingsTab.visible` to decide whether to offer the Logs button.
 *
 * @property title User-facing label. NOTE: hardcoded English; not localized.
 * @property icon Material icon for the section.
 *
 * Filtering contract: [visible] is the authoritative list of entries the UI
 * should offer; callers must consult [visible] (not [entries]) so build-flavor
 * gating is honored.
 */
enum class SettingsTab(
    val title: String,
    val icon: ImageVector,
) {
    // Order determines tab display order in the navigation rail.
    PHONES("Phones", Icons.Default.PhoneAndroid),
    CONTROL("Control", Icons.Default.Settings),
    LOGS("Logs", Icons.AutoMirrored.Filled.Article),
    ;

    companion object {
        /**
         * Entries filtered by current build flavor.
         *
         * Recomputed on every access (called per recomposition at
         * SettingsScreen.kt:172); intentional and cheap — three-entry filter
         * over a compile-time-constant condition, results are not cached.
         *
         * Caveat: no invariant pins `selectedTab in visible`. If a caller ends
         * up with a `selectedTab` absent from `visible` (e.g. LOGS while hidden),
         * the rail will show no selection while the content pane still renders
         * that tab.
         */
        val visible: List<SettingsTab>
            get() =
                entries.filter { tab ->
                    when (tab) {
                        // SUSPICIOUS: inverted predicate — hides LOGS in DEBUG,
                        // shows it in RELEASE. Reads backwards for a developer-aid
                        // tab; load-bearing, preserve as-is but re-verify product
                        // intent before touching.
                        LOGS -> !BuildConfig.DEBUG
                        else -> true
                    }
                }
    }
}
