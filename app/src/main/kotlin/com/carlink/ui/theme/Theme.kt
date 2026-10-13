package com.carlink.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// This app is permanently dark-themed by design (all-black window/Compose backgrounds
// elsewhere); there is deliberately no light scheme. Some AAOS builds report
// `isSystemInDarkTheme() == false` regardless of the GM skin's actual (dark) appearance, so a
// system-following theme previously leaked a light scheme's near-white
// surfaceVariant/surfaceContainerHighest tones into any composable reading them directly
// (confirmed live: forcing system night mode on made the leak disappear). Don't reintroduce a
// light/system-following scheme without also auditing every `MaterialTheme.colorScheme.*` use.
// (`isSystemInDarkTheme()` in MainScreen.kt drives the separate CarPlay night-mode protocol
// message to the phone; it has nothing to do with this app's own rendering.)

/**
 * Dark color scheme chosen for this project; no automotive-specific luminance/glare tuning
 * has been applied to these hex values (standard M3 teal palette, not AAOS-tuned).
 */
private val DarkColorScheme =
    darkColorScheme(
        // Primary colors - Dark teal/cyan based on #003E49
        primary = Color(0xFF5FD5ED), // Light cyan for primary actions
        onPrimary = Color(0xFF003640), // Dark teal text on primary
        primaryContainer = Color(0xFF004E5C), // Medium teal container
        onPrimaryContainer = Color(0xFFB5EEFF), // Light cyan text on container
        // Secondary colors - Complementary cool tones
        secondary = Color(0xFFB0CBCE), // Light blue-gray
        onSecondary = Color(0xFF1B3438), // Dark blue-gray
        secondaryContainer = Color(0xFF324B4F), // Medium blue-gray container
        onSecondaryContainer = Color(0xFFCCE7EA), // Very light blue-gray
        // Tertiary colors - Warm accent (yellow-orange for warnings)
        tertiary = Color(0xFFFFB951), // Warm yellow-orange
        onTertiary = Color(0xFF432C00), // Dark brown
        tertiaryContainer = Color(0xFF604000), // Medium brown container
        onTertiaryContainer = Color(0xFFFFDDB0), // Light cream
        // Error colors - Bright red for destructive actions (Material tone 40)
        error = Color(0xFFBA1A1A), // Deep vibrant red (Material tone 40; prior comment said tone 20 — incorrect)
        onError = Color(0xFFFFFFFF), // White text on red
        errorContainer = Color(0xFF93000A), // Dark red container
        onErrorContainer = Color(0xFFFFDAD6), // Light pink text
        // Surface colors - Dark backgrounds with proper elevation
        surface = Color(0xFF0E1415), // Deep dark teal-gray
        onSurface = Color(0xFFDDE4E5), // Light gray text
        surfaceContainerHighest = Color(0xFF30393A), // Elevated surfaces (cards)
        surfaceContainerHigh = Color(0xFF252E2F),
        surfaceContainer = Color(0xFF1A2324),
        surfaceContainerLow = Color(0xFF171D1E),
        surfaceContainerLowest = Color(0xFF090F10),
        surfaceVariant = Color(0xFF30393A), // Same as surfaceContainerHighest — design smell: two semantically distinct M3 roles collapsed to one hex. Future: split them.
        onSurfaceVariant = Color(0xFF889394), // Outline color for secondary text
        // Outline colors - Borders and dividers
        outline = Color(0xFF889394),
        outlineVariant = Color(0xFF3F484A),
        // Scrim - Modal overlays
        scrim = Color(0xFF000000),
        // Inverse colors - Contrasting elements
        inverseSurface = Color(0xFFDDE4E5),
        inverseOnSurface = Color(0xFF2B3132),
        inversePrimary = Color(0xFF006780),
    )

/**
 * Material 3 theme for this project, always dark (see the comment above [DarkColorScheme]).
 * Colors/typography were chosen for this app; no automotive-specific luminance tuning has been
 * applied (despite file naming).
 *
 * @param dynamicColor whether to use Android 12+ dynamic color. Disabled by default for
 *   consistent branding. When `true` on API 31+ it resolves to `dynamicDarkColorScheme` only
 *   (never the light variant); on API <31 it is ignored by the `SDK_INT` guard and the static
 *   [DarkColorScheme] is used.
 * @param content composable content to theme.
 */
@Composable
fun CarlinkTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Dynamic color is API 31+. Guard with SDK_INT so the API-31 call is never reached on
    // minSdk 29 (resolves lint [NewApi]); pre-31 falls back to DarkColorScheme even if a
    // caller passes dynamicColor = true.
    val colorScheme =
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dynamicDarkColorScheme(androidx.compose.ui.platform.LocalContext.current)
        } else {
            DarkColorScheme
        }

    val view = LocalView.current
    if (!view.isInEditMode) {
        // KNOWN FRAGILITY: `view.context as Activity` is an unchecked cast guarded only by
        // `!view.isInEditMode`. It will ClassCastException under non-Activity hosts
        // (Service, Dialog with a wrapped context, ContextThemeWrapper, app widgets, etc.).
        //
        // KNOWN INEFFICIENCY: `SideEffect` runs on every successful recomposition, so the
        // window/insets writes below re-apply each time. They are idempotent (no visible
        // flicker), but this could be a keyed effect so the work only runs once.
        SideEffect {
            val window = (view.context as Activity).window
            // System-bar background colors are intentionally NOT set here. Window
            // statusBarColor / navigationBarColor are deprecated (edge-to-edge enforcement),
            // and MainActivity already calls enableEdgeToEdge(), drawing content behind
            // transparent system bars. We only drive the bar ICON appearance: light icons
            // (appearance-light = false) since the app is always dark; the bar scrims are
            // handled by edge-to-edge.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CarlinkTypography,
        content = content,
    )
}

/**
 * App typography. Intentionally overrides only three roles (displayLarge, titleLarge,
 * labelLarge) to bump weight; `body*` and other roles are left at M3 defaults by design.
 * Note: despite the automotive-legibility framing elsewhere in this file, body roles are
 * not tuned here — revisit if in-car readability testing flags them.
 */
val CarlinkTypography =
    Typography(
        // Large display for status text
        displayLarge =
            Typography().displayLarge.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            ),
        // Titles for sections
        titleLarge =
            Typography().titleLarge.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            ),
        // Labels for buttons (larger for touch targets)
        labelLarge =
            Typography().labelLarge.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            ),
    )

/**
 * Shared dimension tokens used by in-app controls.
 * Design context: `ButtonMinHeight = 72.dp` is marginal vs AAOS guidance (~76.dp) — acceptable
 * but worth revisiting if touch-target audits flag it. `IconSize = 28.dp` is a project choice,
 * larger than the M3 default (24.dp) for in-vehicle glanceability.
 */
object AutomotiveDimens {
    val ButtonMinHeight = 72.dp // Marginal vs AAOS ~76.dp min touch target guidance.
    val ButtonPaddingHorizontal = 24.dp
    val ButtonPaddingVertical = 20.dp
    val IconSize = 28.dp // Larger than M3 default (24.dp) for in-vehicle glanceability.
}
