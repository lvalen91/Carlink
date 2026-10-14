package com.carlink.ui.settings

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout variant the settings dashboard composes itself into. Selected at runtime from the
 * MEASURED content area (see [classifyDashboardLayout]) because this app has no advance
 * knowledge of the head unit's panel shape: GM radios range from ultrawide landscape
 * (gminfo37 2400x960) to tall portrait (Silverado EV-style vertical center stack) to roughly
 * 4:3 boxes, at any density.
 *
 * - [LANDSCAPE]: two panes side by side — an action rail on the start edge, known devices
 *   as a horizontally scrolling row of tiles filling the rest.
 * - [BOXY]: one column — header, a horizontal strip of action buttons, then the known
 *   devices as a horizontally scrolling row of tiles. Used for ~4:3 / 3:2 panels where a
 *   two-pane split would leave neither pane enough width.
 * - [PORTRAIT]: one vertically scrolling column — header, stacked action buttons, then the
 *   known devices as a stack of row cards (topmost = most prominent).
 *
 * The device list's scroll axis follows the variant: LANDSCAPE/BOXY scroll horizontally
 * (leftmost = most prominent), PORTRAIT scrolls vertically (topmost = most prominent).
 */
enum class DashboardLayout {
    PORTRAIT,
    BOXY,
    LANDSCAPE,
    ;

    /** True when the known-devices list should scroll horizontally (row of tiles). */
    val devicesScrollHorizontally: Boolean
        get() = this != PORTRAIT
}

/**
 * Breakpoints used by [classifyDashboardLayout].
 *
 * The width thresholds are the Material 3 window-size-class boundaries
 * (compact < 600dp, medium 600..839dp, expanded >= 840dp — Material 3 "Layout > Applying
 * layout > Window size classes"); Material's adaptive guidance reserves two-pane layouts for
 * the expanded class, which is why LANDSCAPE additionally requires [ExpandedMinWidth]. The
 * aspect threshold separates "boxy" panels (4:3 = 1.33, 3:2 = 1.5) from genuinely wide ones
 * (16:10 = 1.6, 16:9 = 1.78, gminfo37 = 2.5); it is a judgement call, not a Material value,
 * chosen so a 3:2 panel keeps its single-column layout and 16:10 and wider get two panes.
 *
 * Supported floor (owner, 2026-10-13): 1280x720 px is the lowest projection resolution
 * CarPlay / Android Auto support, so panels below that are not a design target. Density can
 * still shrink 1280x720 px to a compact DP width (640x360dp at 320 dpi), which is why the
 * compact handling below exists; it is not there to serve sub-720p panels.
 */
object DashboardBreakpoints {
    /** Below this width every variant collapses to the single-column PORTRAIT composition. */
    val CompactMaxWidth: Dp = 600.dp

    /** Two-pane LANDSCAPE needs at least the Material "expanded" width class. */
    val ExpandedMinWidth: Dp = 840.dp

    /** width/height at or above this is treated as wide landscape rather than boxy. */
    const val WIDE_ASPECT: Float = 1.6f
}

/**
 * Pick the [DashboardLayout] for a content area of [contentWidth] x [contentHeight] (dp,
 * AFTER system-bar and cutout insets have been removed — the `BoxWithConstraints` maxima of
 * the inset-padded box) on a panel whose overall shape is [panelAspect] (window width /
 * height, i.e. `LocalWindowInfo.containerSize`, which spans the full display edge-to-edge).
 *
 * Why two inputs: the SHAPE decision must come from the panel, not the content area. With
 * the system bars visible a 4:3 panel's content area is ~1.6:1 (verified: 1280x960 window ->
 * 1280x788dp content), so classifying on content aspect made the same radio flip between
 * BOXY and LANDSCAPE purely as a side effect of the user's Display Mode choice. The panel
 * aspect is stable across display modes. The WIDTH decisions (compact / expanded) still use
 * the content area, because that is the space the buttons and tiles actually get.
 *
 * Rules, in priority order:
 *  1. panel taller than wide, content taller than wide, or content narrower than the
 *     Material compact boundary -> PORTRAIT
 *  2. wide panel aspect AND content at least Material expanded width -> LANDSCAPE
 *  3. everything else (4:3, 3:2, or medium-width landscape) -> BOXY
 */
fun classifyDashboardLayout(
    contentWidth: Dp,
    contentHeight: Dp,
    panelAspect: Float,
): DashboardLayout {
    if (contentWidth <= 0.dp || contentHeight <= 0.dp) return DashboardLayout.PORTRAIT
    val contentAspect = contentWidth / contentHeight
    val aspect = if (panelAspect > 0f) panelAspect else contentAspect
    return when {
        aspect < 1f || contentAspect < 1f || contentWidth < DashboardBreakpoints.CompactMaxWidth ->
            DashboardLayout.PORTRAIT
        aspect >= DashboardBreakpoints.WIDE_ASPECT && contentWidth >= DashboardBreakpoints.ExpandedMinWidth ->
            DashboardLayout.LANDSCAPE
        else -> DashboardLayout.BOXY
    }
}

/**
 * Spacing tokens for the dashboard. Only the outer gutter adapts to width (compact panels
 * can't afford 24dp on every edge); everything else is a fixed Material 8dp-grid value.
 */
object DashboardDimens {
    /** Outer page gutter. */
    fun gutter(width: Dp): Dp = if (width < DashboardBreakpoints.CompactMaxWidth) 16.dp else 24.dp

    /** Gap between sibling cards / buttons. */
    val Gap: Dp = 16.dp

    /**
     * Inner padding of the two section cards (controls / known devices). 16dp so a card's
     * extraLarge (28dp) corner is concentric with the 12dp corner of the device tiles it holds
     * (outer radius = inner radius + padding).
     */
    val CardPadding: Dp = 16.dp

    /** Gap between stacked action buttons. */
    val ButtonGap: Dp = 12.dp

    /** Narrowest a device tile in the horizontal list may get (fits a 2-line name + Remove). */
    val TileMinWidth: Dp = 220.dp

    /** Preferred tile width; the row fits as many of these as it can, then stretches them. */
    val TilePreferredWidth: Dp = 300.dp

    /** Widest a tile may stretch to (avoids a single 1000dp card on wide panes). */
    val TileMaxWidth: Dp = 420.dp

    /** Tallest a tile may stretch to in LANDSCAPE (content is top-anchored; taller is empty). */
    val TileMaxHeight: Dp = 440.dp

    /** LANDSCAPE action rail width, as a fraction of the content width, then clamped. */
    const val RAIL_WIDTH_FRACTION: Float = 0.28f
    val RailMinWidth: Dp = 280.dp
    val RailMaxWidth: Dp = 420.dp

    /** PORTRAIT/BOXY: action buttons go two-abreast at or above this width. */
    val TwoColumnActionsMinWidth: Dp = 560.dp
}

/**
 * Width for each tile in a horizontally scrolling device row of [rowWidth] holding
 * [itemCount] tiles.
 *
 * When everything fits, an integer number of [DashboardDimens.TilePreferredWidth]-ish tiles
 * fills the row exactly (no half-empty trailing gap). When the list OVERFLOWS, the tiles are
 * sized so that half of the next one peeks in at the end — the standard carousel
 * affordance that tells the user the row scrolls (an exactly-filled row of N tiles looks
 * final even when N+2 devices exist). Clamped to [DashboardDimens.TileMinWidth]..
 * [TileMaxWidth]; when the clamp kicks in the row simply scrolls.
 */
fun deviceTileWidth(
    rowWidth: Dp,
    itemCount: Int,
    gap: Dp = DashboardDimens.Gap,
): Dp {
    if (rowWidth <= 0.dp) return DashboardDimens.TileMinWidth
    val columns = ((rowWidth + gap) / (DashboardDimens.TilePreferredWidth + gap)).toInt().coerceAtLeast(1)
    val stretched =
        if (itemCount > columns) {
            (rowWidth - gap * columns) / (columns + PEEK_FRACTION)
        } else {
            (rowWidth - gap * (columns - 1)) / columns
        }
    return stretched.coerceIn(DashboardDimens.TileMinWidth, DashboardDimens.TileMaxWidth)
}

/** How much of the first off-screen tile stays visible when the device row overflows. */
private const val PEEK_FRACTION = 0.5f
