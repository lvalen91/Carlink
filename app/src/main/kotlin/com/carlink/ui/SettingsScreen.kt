package com.carlink.ui

import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.CarlinkManager
import com.carlink.logging.FileLogManager
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.protocol.PhoneType
import com.carlink.ui.settings.AdapterActionsDialog
import com.carlink.ui.settings.AdapterConfigPreference
import com.carlink.ui.settings.AdapterConfigurationDialog
import com.carlink.ui.settings.AppControlActionsDialog
import com.carlink.ui.settings.DashboardDimens
import com.carlink.ui.settings.DashboardLayout
import com.carlink.ui.settings.DashboardModal
import com.carlink.ui.settings.DisplayMode
import com.carlink.ui.settings.DisplayModeDialog
import com.carlink.ui.settings.DisplayModePreference
import com.carlink.ui.settings.KnownDevicesList
import com.carlink.ui.settings.LogsTabContent
import com.carlink.ui.settings.SessionSnapshot
import com.carlink.ui.settings.SettingsTab
import com.carlink.ui.settings.classifyDashboardLayout
import com.carlink.ui.settings.rememberDeviceTileMinHeight
import com.carlink.ui.settings.rememberSessionSnapshot
import com.carlink.ui.theme.AutomotiveDimens
import com.carlink.util.DebugProps
import kotlinx.coroutines.launch

/** Blur radius for the frozen-frame backdrop. No-op below API 31 (see SurfaceSnapshot.kt). */
private val BACKDROP_BLUR_RADIUS = 24.dp

/** Surface-colored scrim over the blurred frame so cards and text keep their contrast. */
private const val BACKDROP_SCRIM_ALPHA = 0.62f

/** Top-level pages inside the settings overlay. LOGS is a full page, not a modal. */
private enum class DashboardPage { HOME, LOGS }

/**
 * The app's base screen (dashboard): a single adaptive layout that re-composes itself from
 * the measured content area (see [DashboardLayout] / [classifyDashboardLayout]) instead of
 * assuming any panel shape. It is what the user sees whenever no projection is covering it —
 * including at cold start, before any adapter has ever connected. Hosts:
 *  - the known-devices list (orientation-aware scroll axis),
 *  - "Box Configuration" and "App Control" as buttons that open sequenced popups
 *    (see [DashboardModal] for why popups replace rather than stack),
 *  - Logs as a secondary page (build-gated via [SettingsTab.visible]),
 *  - "Reset Box" (one-tap adapter-session reset; see [onResetConnection]) and the app version
 *    (Close App, with its confirmation, lives inside the App Control popup),
 *  - while a session is streaming but hidden (the phone's REQUEST_HOST_UI revealed this
 *    screen), a primary "Return to CarPlay / Android Auto" action as the first button.
 *
 * @param onReturnToProjection Re-shows the live projection over this screen. Null when there is
 *   no streaming session to return to; the button is then absent. Driven by CarlinkApp's
 *   immediate state mirror, not this screen's 1 Hz [rememberSessionSnapshot] poll, so the
 *   button appears/disappears in the same frame as the projection does.
 * @param onResetConnection Activity-level manager rebuild (MainActivity.reinitializeForDisplayMode).
 *   The dashboard's "Reset Box" button calls it. Null falls back to an in-manager restart.
 * @param sessionBackdrop Frozen frame of the live projection captured when this screen was
 *   revealed over it (null when there is no session or the capture failed). Drawn blurred
 *   under the dashboard.
 *
 * Compose dashboard; length is declarative UI layout plus the modal state machine.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
@Composable
fun SettingsScreen(
    carlinkManager: CarlinkManager,
    fileLogManager: FileLogManager?,
    onResetCluster: () -> Unit,
    onReturnToProjection: (() -> Unit)? = null,
    onReinitForDisplayMode: (DisplayMode) -> Unit = {},
    onResetConnection: (() -> Unit)? = null,
    sessionBackdrop: ImageBitmap? = null,
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        logInfo(
            "[UI_STATE] Dashboard visible" +
                if (onReturnToProjection != null) " (streaming session hidden behind it)" else " (no projection)",
            tag = "UI",
        )
    }

    val appVersion =
        remember {
            try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                "${packageInfo.versionName}+${packageInfo.longVersionCode}"
            } catch (e: PackageManager.NameNotFoundException) {
                logWarn("[SettingsScreen] Failed to get package info: ${e.message}", tag = "UI")
                "Unknown"
            }
        }

    val session by rememberSessionSnapshot(carlinkManager)

    var page by remember { mutableStateOf(DashboardPage.HOME) }
    var modal by remember { mutableStateOf(DashboardModal.NONE) }
    // Shared processing flag for the control popups (see AppControlActionsDialog KDoc).
    // Hoisted here, with the screen-scoped coroutine scope, so dismissing a popup mid-reset
    // doesn't cancel the restart() coroutine the way a dialog-local scope would.
    var isProcessing by remember { mutableStateOf(false) }

    LaunchedEffect(page) { logInfo("[UI_STATE] Settings page: $page", tag = "UI") }
    LaunchedEffect(modal) { logInfo("[UI_STATE] Settings modal: $modal", tag = "UI") }

    // Logs page back-navigation. Composed after CarlinkApp's overlay BackHandler, so it takes
    // precedence while enabled; dialogs handle their own back via onDismissRequest.
    BackHandler(enabled = page != DashboardPage.HOME) { page = DashboardPage.HOME }

    val displayModePreference = remember { DisplayModePreference.getInstance(context) }
    // Initial value is the platform-aware default; the flow overwrites it with any persisted
    // user choice on first emission.
    val displayModeInitial = remember { DisplayMode.platformDefault(context) }
    val currentDisplayMode by displayModePreference.displayModeFlow.collectAsStateWithLifecycle(
        initialValue = displayModeInitial,
    )
    val adapterConfigPreference = remember { AdapterConfigPreference.getInstance(context) }
    // Reactive (was a one-shot remember{} snapshot that went stale after toggling the setting
    // inside AdapterConfigurationDialog — the fix the old code's comment asked for).
    val clusterNavigationEnabled by adapterConfigPreference.clusterNavigationFlow.collectAsStateWithLifecycle(
        initialValue = remember { adapterConfigPreference.getClusterNavigationSync() },
    )

    fun haptic() = view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    val resetConnection: () -> Unit = {
        logWarn("[UI_ACTION] Reset Connection button clicked", tag = "UI")
        val reset = onResetConnection
        if (reset != null) {
            // Same path as MainScreen's "Reset Device": the Activity rebuilds the manager,
            // which drops this whole composition — nothing local to clear afterwards.
            modal = DashboardModal.NONE
            reset()
        } else {
            isProcessing = true
            scope.launch {
                try {
                    carlinkManager.restart()
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    // The remembered callbacks below outlive recompositions; read the reset lambda through
    // updated state so a recomposition-supplied onResetConnection is never captured stale.
    val currentResetConnection by rememberUpdatedState(resetConnection)

    val callbacks =
        remember(carlinkManager, onReturnToProjection) {
            DashboardCallbacks(
                onReturnToProjection =
                    onReturnToProjection?.let { returnToProjection ->
                        {
                            haptic()
                            returnToProjection()
                        }
                    },
                onAdapterConfiguration = {
                    haptic()
                    modal = DashboardModal.ADAPTER_ACTIONS
                },
                onAppControl = {
                    haptic()
                    modal = DashboardModal.APP_CONTROL_ACTIONS
                },
                onLogs =
                    // Logs is build-gated (hidden in DEBUG per SettingsTab.visible);
                    // `adb shell setprop debug.carlink.show_logs 1` forces it in debug builds
                    // so the 4-button arrangement and the Logs page can be checked.
                    if (SettingsTab.LOGS in SettingsTab.visible || DebugProps.isSet("debug.carlink.show_logs")) {
                        {
                            haptic()
                            page = DashboardPage.LOGS
                        }
                    } else {
                        null
                    },
                onResetBox = {
                    // Inside App Control this button disabled itself while the in-manager
                    // restart fallback ran; the dashboard button has no disabled state, so
                    // drop re-taps here instead of launching overlapping restarts.
                    if (!isProcessing) {
                        haptic()
                        currentResetConnection()
                    }
                },
            )
        }

    // Surface (not a plain Box + background) so LocalContentColor resolves to onSurface for
    // every un-colored Text below; it is also the opaque base that hides the projection.
    Surface(modifier = Modifier.fillMaxSize(), color = colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Frozen frosted-glass backdrop: the captured projection frame, blurred, under a
            // surface-tinted scrim. Only present while overlaying a live session AND the capture
            // succeeded; otherwise the opaque surface is all there is (no live bleed-through).
            if (sessionBackdrop != null) {
                Image(
                    bitmap = sessionBackdrop,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(BACKDROP_BLUR_RADIUS),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(colorScheme.surface.copy(alpha = BACKDROP_SCRIM_ALPHA)),
                )
            }

            when (page) {
                DashboardPage.HOME ->
                    DashboardHome(
                        carlinkManager = carlinkManager,
                        session = session,
                        appVersion = appVersion,
                        callbacks = callbacks,
                    )

                DashboardPage.LOGS ->
                    LogsPage(
                        fileLogManager = fileLogManager,
                        onBack = {
                            haptic()
                            page = DashboardPage.HOME
                        },
                    )
            }
        }
    }

    // ---- Modals: exactly one in composition at a time (see DashboardModal). ----
    when (modal) {
        DashboardModal.NONE -> Unit

        DashboardModal.ADAPTER_ACTIONS ->
            AdapterActionsDialog(
                session = session,
                isProcessing = isProcessing,
                onConfigure = { modal = DashboardModal.ADAPTER_CONFIG },
                onDisconnectPhone = {
                    logWarn("[UI_ACTION] Disconnect Phone button clicked", tag = "UI")
                    carlinkManager.disconnectPhone()
                    modal = DashboardModal.NONE
                },
                onDisconnectAdapter = {
                    logWarn("[UI_ACTION] Disconnect Adapter button clicked", tag = "UI")
                    carlinkManager.stop()
                    modal = DashboardModal.NONE
                },
                onDismiss = { modal = modal.parent },
            )

        DashboardModal.APP_CONTROL_ACTIONS ->
            AppControlActionsDialog(
                currentDisplayMode = currentDisplayMode,
                isProcessing = isProcessing,
                onDisplayMode = { modal = DashboardModal.DISPLAY_MODE },
                onResetDecoder = {
                    logWarn("[UI_ACTION] Reset Decoder button clicked", tag = "UI")
                    carlinkManager.resetVideoDecoder()
                    modal = DashboardModal.NONE
                },
                onResetCluster = {
                    modal =
                        if (clusterNavigationEnabled) {
                            DashboardModal.RESET_CLUSTER_CONFIRM
                        } else {
                            DashboardModal.CLUSTER_NAV_OFF
                        }
                },
                onCloseApp = { modal = DashboardModal.CLOSE_APP_CONFIRM },
                onDismiss = { modal = modal.parent },
            )

        DashboardModal.ADAPTER_CONFIG ->
            AdapterConfigurationDialog(
                adapterConfigPreference = adapterConfigPreference,
                carlinkManager = carlinkManager,
                currentDisplayMode = currentDisplayMode,
                // The dialog reports Cancel, Default, outside-tap AND Apply-completion through
                // the same onDismiss, so all of them step back to the Adapter popup; the
                // Tier-2 reinit path below closes everything (the composition is rebuilt anyway).
                onDismiss = { modal = modal.parent },
                onReinitAdapter = {
                    modal = DashboardModal.NONE
                    onReinitForDisplayMode(currentDisplayMode)
                },
            )

        DashboardModal.DISPLAY_MODE ->
            DisplayModeDialog(
                displayModePreference = displayModePreference,
                onDismiss = { modal = modal.parent },
                onApplyAndRestart = { newMode ->
                    modal = DashboardModal.NONE
                    scope.launch {
                        displayModePreference.setDisplayMode(newMode)
                        logInfo(
                            "[DISPLAY_REINIT] User applied display mode: ${newMode.name} — " +
                                "reinitializing in-place (no app kill)",
                            tag = "UI",
                        )
                        // Tier-2 session restart: tear down adapter, apply new mode,
                        // recalculate resolution, rebuild CarlinkManager.
                        onReinitForDisplayMode(newMode)
                    }
                },
            )

        DashboardModal.RESET_CLUSTER_CONFIRM ->
            AlertDialog(
                onDismissRequest = { modal = modal.parent },
                icon = { Icon(imageVector = Icons.Default.Speed, contentDescription = null, tint = colorScheme.tertiary) },
                title = { Text("Reset Cluster?") },
                text = {
                    Text("The cluster display will be reset. Helper App will reload and take focus momentarily.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            logWarn("[UI_ACTION] Reset Cluster confirmed", tag = "UI")
                            modal = DashboardModal.NONE
                            onResetCluster()
                        },
                    ) {
                        Text("Reset", color = colorScheme.tertiary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { modal = modal.parent }) { Text("Cancel") }
                },
            )

        DashboardModal.CLUSTER_NAV_OFF ->
            AlertDialog(
                onDismissRequest = { modal = modal.parent },
                icon = { Icon(imageVector = Icons.Default.Speed, contentDescription = null, tint = colorScheme.onSurfaceVariant) },
                title = { Text("Cluster Navigation Off") },
                text = {
                    Text("Cluster Navigation is disabled. Enable it in Box Configuration to use this feature.")
                },
                confirmButton = {
                    TextButton(onClick = { modal = modal.parent }) { Text("OK") }
                },
            )

        DashboardModal.CLOSE_APP_CONFIRM ->
            AlertDialog(
                onDismissRequest = { modal = modal.parent },
                title = { Text("Close App?") },
                text = { Text("This will stop all connections and close the app.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            logWarn("[UI_ACTION] Close App confirmed", tag = "UI")
                            // Graceful teardown: sends 0x0F+0x15 to the adapter.
                            carlinkManager.stop()
                            // Silent no-op if the context is not an Activity; SettingsScreen is
                            // always hosted by MainActivity today.
                            (context as? android.app.Activity)?.finishAffinity()
                        },
                    ) {
                        Text("Close", color = colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { modal = modal.parent }) { Text("Cancel") }
                },
            )
    }
}

/**
 * Click handlers for the dashboard's fixed controls. [onLogs] is null when Logs is build-gated
 * out; [onReturnToProjection] is null when no streaming session is hidden behind the dashboard.
 */
@Stable
private class DashboardCallbacks(
    val onReturnToProjection: (() -> Unit)?,
    val onAdapterConfiguration: () -> Unit,
    val onAppControl: () -> Unit,
    val onLogs: (() -> Unit)?,
    val onResetBox: () -> Unit,
)

// ==================== Home page: adaptive layout ====================

/**
 * Measures the inset-padded content area and composes one of the three [DashboardLayout]
 * variants. Everything below this point receives the measured size, never the window size.
 */
@Composable
private fun DashboardHome(
    carlinkManager: CarlinkManager,
    session: SessionSnapshot,
    appVersion: String,
    callbacks: DashboardCallbacks,
) {
    // Panel shape (full window, edge-to-edge) drives the layout CLASS; the inset-padded
    // measured area below drives every dimension. See classifyDashboardLayout for why.
    val windowSize = LocalWindowInfo.current.containerSize
    val panelAspect =
        if (windowSize.width > 0 && windowSize.height > 0) {
            windowSize.width.toFloat() / windowSize.height.toFloat()
        } else {
            0f
        }
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val layout = classifyDashboardLayout(maxWidth, maxHeight, panelAspect)
        val gutter = DashboardDimens.gutter(maxWidth)
        LaunchedEffect(layout, maxWidth, maxHeight) {
            logInfo(
                "[UI_LAYOUT] Settings dashboard layout=$layout for $maxWidth x $maxHeight " +
                    "(panel ${windowSize.width}x${windowSize.height}, aspect=$panelAspect)",
                tag = "UI",
            )
        }
        when (layout) {
            DashboardLayout.LANDSCAPE ->
                LandscapeDashboard(carlinkManager, session, appVersion, callbacks, maxWidth, maxHeight, gutter)

            DashboardLayout.BOXY ->
                BoxyDashboard(carlinkManager, session, appVersion, callbacks, maxWidth, maxHeight, gutter)

            DashboardLayout.PORTRAIT ->
                PortraitDashboard(carlinkManager, session, appVersion, callbacks, maxWidth, maxHeight, gutter)
        }
    }
}

/**
 * LANDSCAPE: two sections side by side — the action rail (status block, action buttons,
 * version, stacked at their natural heights) and the known devices as a row of tiles, both
 * vertically centred in the pane.
 *
 * Centring: the rail + tile group is centred horizontally as ONE composition within the
 * content bounds while the tiles fit (the device pane is weight(fill = false), so it only
 * takes the width its tiles need); once the list overflows the pane takes all remaining
 * width and the row scrolls, which is the same layout as an edge-to-edge rail. Vertically,
 * the device pane balances its "Known devices" label with an equal spacer under the card
 * (only out of genuine slack) so the tiles sit on the content area's centre line, and the
 * rail mirrors that spacer (again only out of its own slack) so the two cards share one
 * centre line. Both columns scroll instead of clipping when the panel is shorter than their
 * content.
 *
 * Cards: two separate [DashboardCard]s that each hug their content — the rail card is its
 * status block + buttons + version, never stretched to the pane (stretching it left two
 * large empty slots inside it on tall panels); the devices card is the tile row's natural
 * width and height. Leftover pane height is plain background outside the cards. Every
 * height below is the pane height minus the card's own vertical padding, so the cards add
 * no scrolling the bare layout didn't have.
 */
@Suppress("LongParameterList")
@Composable
private fun LandscapeDashboard(
    carlinkManager: CarlinkManager,
    session: SessionSnapshot,
    appVersion: String,
    callbacks: DashboardCallbacks,
    contentWidth: Dp,
    contentHeight: Dp,
    gutter: Dp,
) {
    val railWidth =
        (contentWidth * DashboardDimens.RAIL_WIDTH_FRACTION)
            .coerceIn(DashboardDimens.RailMinWidth, DashboardDimens.RailMaxWidth)
    val density = LocalDensity.current
    val sectionLabelBlock = with(density) { MaterialTheme.typography.titleMedium.lineHeight.toDp() } + 12.dp
    val paneHeight = contentHeight - gutter * 2
    // Height available INSIDE either card (both pad their content by CardPadding).
    val cardInnerHeight = paneHeight - DashboardDimens.CardPadding * 2
    val minTileHeight = rememberDeviceTileMinHeight()
    val tileHeight =
        (cardInnerHeight - sectionLabelBlock)
            .coerceIn(minTileHeight, maxOf(minTileHeight, DashboardDimens.TileMaxHeight))
    // Spacer under the devices card mirroring the label above the tiles, taken only from
    // slack so it can never push the pane into scrolling; keeps the tiles (not the card) on
    // the same centre line as the rail's buttons.
    val labelBalance = (cardInnerHeight - sectionLabelBlock - tileHeight).coerceIn(0.dp, sectionLabelBlock)

    Row(
        modifier = Modifier.fillMaxSize().padding(gutter),
        horizontalArrangement = Arrangement.Center,
    ) {
        LandscapeRail(
            session = session,
            appVersion = appVersion,
            callbacks = callbacks,
            railWidth = railWidth,
            paneHeight = paneHeight,
            centreBalance = labelBalance,
        )

        Spacer(modifier = Modifier.width(gutter))

        Column(
            modifier =
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            DashboardCard {
                KnownDevicesList(
                    carlinkManager = carlinkManager,
                    session = session,
                    layout = DashboardLayout.LANDSCAPE,
                    modifier = Modifier.padding(vertical = DashboardDimens.CardPadding),
                    contentPadding = PaddingValues(horizontal = DashboardDimens.CardPadding),
                    tileHeight = tileHeight,
                    header = { DevicesSectionLabel(inset = DashboardDimens.CardPadding) },
                )
            }
            Spacer(modifier = Modifier.height(labelBalance))
        }
    }
}

/**
 * LANDSCAPE action rail: status header, the stacked action buttons, the version label, all in
 * one [DashboardCard] that hugs them (no inflated slots) and is vertically centred in the
 * pane. The card is [railWidth] + its padding wide, so the content keeps the rail width it
 * had before the card existed (the header's two-line reservation is sized for
 * [DashboardDimens.RailMinWidth]); the status block spans that width with each line centred.
 * When the pane is shorter than the content the card is capped at the pane height (weight,
 * fill = false) and the column scrolls inside it, clipped by its shape.
 *
 * [centreBalance] is the devices pane's spacer under its card; the rail puts the same spacer
 * under its own card so both cards sit on one centre line — but only out of the rail's own
 * slack (its content is measured), so mirroring it can never push the rail into scrolling.
 */
@Suppress("LongParameterList")
@Composable
private fun LandscapeRail(
    session: SessionSnapshot,
    appVersion: String,
    callbacks: DashboardCallbacks,
    railWidth: Dp,
    paneHeight: Dp,
    centreBalance: Dp,
) {
    val density = LocalDensity.current
    // Natural card height (content + the card's padding), measured inside the scroll so it is
    // the unconstrained height even when the card itself is capped.
    var contentPx by remember { mutableIntStateOf(0) }
    val balance =
        if (contentPx > 0) {
            (paneHeight - with(density) { contentPx.toDp() }).coerceIn(0.dp, centreBalance)
        } else {
            0.dp
        }

    Column(modifier = Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center) {
        DashboardCard(
            modifier =
                Modifier
                    .width(railWidth + DashboardDimens.CardPadding * 2)
                    .weight(1f, fill = false),
        ) {
            // Padding AFTER verticalScroll so it scrolls with the content (like contentPadding);
            // onSizeChanged between them sees the padded content at its natural height.
            Column(
                modifier =
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .onSizeChanged { contentPx = it.height }
                        .padding(DashboardDimens.CardPadding),
            ) {
                DashboardHeader(session = session, centered = true, modifier = Modifier.fillMaxWidth())
                Box(modifier = Modifier.padding(vertical = DashboardDimens.Gap)) {
                    DashboardActionButtons(callbacks = callbacks, session = session, columns = 1)
                }
                VersionLabel(appVersion = appVersion)
            }
        }
        Spacer(modifier = Modifier.height(balance))
    }
}

/**
 * BOXY: one column of two separate cards — the controls card (header with the version at its
 * end, then the action strip) and the known-devices card holding a row of tiles sized to the
 * remaining height. The controls card's height is measured (onSizeChanged) rather than
 * estimated so the tiles fill exactly; if the panel is too short for the minimum tile the
 * whole column scrolls.
 *
 * Centring: the controls card spans the full width (a partial last strip row is centred, see
 * [ActionGrid]); the devices card hugs its tile row and is centred horizontally (when the row
 * overflows the card takes the full width and the tiles scroll inside it); and when the tiles
 * hit [DashboardDimens.TileMaxHeight] the leftover height is split so the devices card sits on
 * the content area's vertical centre (a bottom spacer mirrors the controls card, out of slack
 * only).
 */
@Suppress("LongParameterList")
@Composable
private fun BoxyDashboard(
    carlinkManager: CarlinkManager,
    session: SessionSnapshot,
    appVersion: String,
    callbacks: DashboardCallbacks,
    contentWidth: Dp,
    contentHeight: Dp,
    gutter: Dp,
) {
    val density = LocalDensity.current
    // Controls card + the gap under it, measured as one block.
    var controlsPx by remember { mutableIntStateOf(0) }
    val controlsDp = with(density) { controlsPx.toDp() }
    val sectionLabelBlock = with(density) { MaterialTheme.typography.titleMedium.lineHeight.toDp() } + 12.dp
    val paneHeight = contentHeight - gutter * 2
    // The devices card's vertical padding: height the tiles can't have.
    val devicesCardChrome = DashboardDimens.CardPadding * 2
    val minTileHeight = rememberDeviceTileMinHeight()
    val measured = controlsPx > 0
    val tileHeight =
        (paneHeight - controlsDp - devicesCardChrome - sectionLabelBlock)
            .coerceIn(minTileHeight, maxOf(minTileHeight, DashboardDimens.TileMaxHeight))
    val controlsBalance =
        if (measured) {
            (paneHeight - controlsDp - devicesCardChrome - sectionLabelBlock - tileHeight).coerceIn(0.dp, controlsDp)
        } else {
            0.dp
        }

    Column(
        modifier = Modifier.fillMaxSize().padding(gutter).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.fillMaxWidth().onSizeChanged { controlsPx = it.height }) {
            DashboardCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(DashboardDimens.CardPadding)) {
                    CardHeaderRow(session = session, appVersion = appVersion)
                    Spacer(modifier = Modifier.height(DashboardDimens.Gap))
                    DashboardActionStrip(
                        callbacks = callbacks,
                        session = session,
                        // The strip only gets the card's inner width.
                        contentWidth = contentWidth - DashboardDimens.CardPadding * 2,
                    )
                }
            }
            Spacer(modifier = Modifier.height(DashboardDimens.Gap))
        }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            DashboardCard {
                KnownDevicesList(
                    carlinkManager = carlinkManager,
                    session = session,
                    layout = DashboardLayout.BOXY,
                    modifier = Modifier.padding(vertical = DashboardDimens.CardPadding),
                    contentPadding = PaddingValues(horizontal = DashboardDimens.CardPadding),
                    tileHeight = if (measured) tileHeight else null,
                    header = { DevicesSectionLabel(inset = DashboardDimens.CardPadding) },
                )
            }
        }
        Spacer(modifier = Modifier.height(controlsBalance))
    }
}

/**
 * PORTRAIT: ONE vertically scrolling column holding two separate cards — the controls card
 * (header with the version at its end, then the action buttons one or two abreast by width)
 * and the known-devices card with its row cards. Everything scrolls together and nothing is
 * weighted, so on a short/narrow panel neither card can crush the other to zero height.
 *
 * Centring: both cards span the gutter-to-gutter width (a lone trailing button is centred,
 * see [ActionGrid]). The controls card is anchored at the top; when everything fits, the
 * devices card is centred on the content area's vertical centre (a bottom spacer mirrors the
 * controls card, out of slack only), the same rule as BOXY. Longer lists scroll the page.
 * The gutter is padding INSIDE the scroll so the cards scroll to the screen edge.
 */
@Suppress("LongParameterList")
@Composable
private fun PortraitDashboard(
    carlinkManager: CarlinkManager,
    session: SessionSnapshot,
    appVersion: String,
    callbacks: DashboardCallbacks,
    contentWidth: Dp,
    contentHeight: Dp,
    gutter: Dp,
) {
    // Buttons only get the card's inner width, so the two-abreast threshold is checked
    // against that rather than the bare content width.
    val columns =
        if (contentWidth - DashboardDimens.CardPadding * 2 >= DashboardDimens.TwoColumnActionsMinWidth) 2 else 1
    val density = LocalDensity.current
    // Controls card + the gap under it, and the devices card, each measured.
    var controlsPx by remember { mutableIntStateOf(0) }
    var devicesPx by remember { mutableIntStateOf(0) }
    val controlsBalance =
        if (controlsPx > 0 && devicesPx > 0) {
            val controlsDp = with(density) { controlsPx.toDp() }
            val devicesDp = with(density) { devicesPx.toDp() }
            (contentHeight - gutter * 2 - controlsDp - devicesDp).coerceIn(0.dp, controlsDp)
        } else {
            0.dp
        }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(gutter),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.fillMaxWidth().onSizeChanged { controlsPx = it.height }) {
            DashboardCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(DashboardDimens.CardPadding)) {
                    CardHeaderRow(session = session, appVersion = appVersion)
                    Spacer(modifier = Modifier.height(DashboardDimens.Gap))
                    DashboardActionButtons(callbacks = callbacks, session = session, columns = columns)
                }
            }
            Spacer(modifier = Modifier.height(DashboardDimens.Gap))
        }
        DashboardCard(modifier = Modifier.fillMaxWidth().onSizeChanged { devicesPx = it.height }) {
            KnownDevicesList(
                carlinkManager = carlinkManager,
                session = session,
                layout = DashboardLayout.PORTRAIT,
                contentPadding = PaddingValues(DashboardDimens.CardPadding),
                header = { DevicesSectionLabel() },
            )
        }
        Spacer(modifier = Modifier.height(controlsBalance))
    }
}

// ==================== Shared building blocks ====================

/**
 * Live connection block in the title slot — "Connect Phone to:" / the adapter's advertised
 * WiFi/BT name / the current phase string ("Searching for box...", "Waiting for phone...",
 * "Streaming", "Reconnecting (2/5)...") — in place of the app name, which told the user
 * nothing. Both values come from the [SessionSnapshot] poll ([CarlinkManager.statusText],
 * [CarlinkManager.adapterName]) because the manager's single-slot callback belongs to
 * MainScreen. No Back button: this screen is the app's base, there is nothing behind it to go
 * back to. Returning to a hidden projection is a first-class action button instead (see
 * [DashboardCallbacks.onReturnToProjection]).
 *
 * Shown whenever the dashboard is, including over a hidden live projection — the phase line
 * then simply reads "Streaming" next to the Return button, which is the state the user wants
 * confirmed there. No extra hide logic.
 *
 * Height is constant for a given name: the phase line reserves two lines (several phases wrap
 * at the LANDSCAPE rail's minimum width), so a phase flipping between one and two lines never
 * re-measures the header — BOXY/PORTRAIT size their body from it and LANDSCAPE mirrors it
 * under the buttons. Name and label are single-line with ellipsis; the name is user-set and
 * unbounded.
 */
@Composable
private fun DashboardHeader(
    session: SessionSnapshot,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
) {
    // centered: each line is centred on its own (not a left-aligned block centred as a whole —
    // that block's width follows its widest line, so a phase change would slide the name
    // sideways). Only the position changes; text, styles and line limits are identical.
    val textAlign = if (centered) TextAlign.Center else TextAlign.Start
    Column(
        modifier = modifier,
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Text(
            text = "Connect Phone to:",
            textAlign = textAlign,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = session.adapterName,
            textAlign = textAlign,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = session.statusText,
            textAlign = textAlign,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * BOXY/PORTRAIT controls-card header: the status block centred on the card's width, with the
 * version tag in the top-right corner. A leading spacer as wide as the MEASURED tag balances
 * it, so the block is centred on the whole card rather than on the space left of the tag.
 * Top-aligned: the tag sits on the "Connect Phone to:" line instead of floating mid-way down
 * the three-line block.
 */
@Composable
private fun CardHeaderRow(
    session: SessionSnapshot,
    appVersion: String,
) {
    val density = LocalDensity.current
    var versionPx by remember { mutableIntStateOf(0) }
    val versionWidth = with(density) { versionPx.toDp() }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Spacer(modifier = Modifier.width(versionWidth + DashboardDimens.Gap))
        DashboardHeader(session = session, modifier = Modifier.weight(1f), centered = true)
        Spacer(modifier = Modifier.width(DashboardDimens.Gap))
        Box(modifier = Modifier.onSizeChanged { versionPx = it.width }) {
            VersionLabel(appVersion = appVersion)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * "Known devices" label + gap, composed inside the devices card above the tiles/rows. [inset]
 * is the horizontal padding that lines it up with the first tile when the tile row's own
 * content padding (which the label does not get) supplies the card's side padding.
 */
@Composable
private fun DevicesSectionLabel(inset: Dp = 0.dp) {
    Column(modifier = Modifier.padding(horizontal = inset)) {
        SectionLabel("Known devices")
        Spacer(modifier = Modifier.height(12.dp))
    }
}

/**
 * Background card for one dashboard section (the controls, or the known devices). The two
 * are always separate cards, never one shared surface.
 *
 * Fill: [surfaceContainerHigh][androidx.compose.material3.ColorScheme.surfaceContainerHigh] —
 * the only DarkColorScheme tone that is a clear step up from the base surface without
 * colliding with what sits inside: device tiles/rows use surfaceContainer (dimmed:
 * surfaceContainerLow) and an active HiCar/unknown tile uses surfaceContainerHighest, so any of
 * those as the card fill would make a tile vanish into it. The tiles read as darker wells set
 * into the card; the tonal action buttons (secondaryContainer) stay lighter than it. The
 * dialogs share the tone but only ever appear over a scrim, so the two never sit side by side
 * at the same brightness; the outlineVariant hairline (dialogs have none) keeps a persistent
 * card's edge crisp over the frosted session backdrop, whose brightness varies.
 *
 * Shape: extraLarge (28dp) — concentric with the 12dp device-tile corners at the 16dp
 * [DashboardDimens.CardPadding]. Surface clips to it, so content scrolled inside a card is cut
 * at the rounded edge.
 */
@Composable
private fun DashboardCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

@Composable
private fun VersionLabel(appVersion: String) {
    Text(
        text = "v$appVersion",
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Visual weight of a dashboard action button. */
private enum class ActionTone {
    /** Default tonal button. */
    NEUTRAL,

    /** The one thing the user most likely wants right now (return to the live projection). */
    PRIMARY,

    /** Error-tinted (Reset Box). */
    DESTRUCTIVE,
}

/** One dashboard-level entry point, styled per [tone]. */
@Composable
private fun DashboardActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ActionTone = ActionTone.NEUTRAL,
) {
    val colorScheme = MaterialTheme.colorScheme
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = AutomotiveDimens.ButtonMinHeight),
        colors =
            when (tone) {
                ActionTone.NEUTRAL -> ButtonDefaults.filledTonalButtonColors()
                ActionTone.PRIMARY ->
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = colorScheme.primary,
                        contentColor = colorScheme.onPrimary,
                    )
                ActionTone.DESTRUCTIVE ->
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = colorScheme.errorContainer,
                        contentColor = colorScheme.onErrorContainer,
                    )
            },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(AutomotiveDimens.IconSize),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

private class DashboardAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val tone: ActionTone = ActionTone.NEUTRAL,
)

/** Label for the return-to-projection action, naming the protocol when it is known. */
private fun returnToProjectionLabel(phoneType: PhoneType?): String =
    when (phoneType) {
        PhoneType.CARPLAY, PhoneType.CARPLAY_WIRELESS -> "Return to CarPlay"
        PhoneType.ANDROID_AUTO -> "Return to Android Auto"
        else -> "Return to projection"
    }

/**
 * The dashboard's action buttons in display order. The return-to-projection action, when
 * available, comes first and is the only [ActionTone.PRIMARY] button on the screen.
 */
private fun DashboardCallbacks.actions(session: SessionSnapshot): List<DashboardAction> =
    buildList {
        onReturnToProjection?.let {
            add(DashboardAction(returnToProjectionLabel(session.phoneType), Icons.Default.Cast, it, ActionTone.PRIMARY))
        }
        add(DashboardAction("Box Configuration", Icons.Default.SettingsInputComponent, onAdapterConfiguration))
        add(DashboardAction("App Control", Icons.Default.DisplaySettings, onAppControl))
        onLogs?.let { add(DashboardAction("Logs", Icons.AutoMirrored.Filled.Article, it)) }
        add(DashboardAction("Reset Box", Icons.Default.Usb, onResetBox, ActionTone.DESTRUCTIVE))
    }

/** Stacked action buttons, [columns] abreast (1 or 2). See [ActionGrid]. */
@Composable
private fun DashboardActionButtons(
    callbacks: DashboardCallbacks,
    session: SessionSnapshot,
    columns: Int,
) {
    ActionGrid(actions = callbacks.actions(session), perRow = columns)
}

/**
 * Horizontal strip of equal-width action buttons (BOXY). Falls back to two rows when the
 * strip would give each button less than the width a 2-line label needs.
 */
@Composable
private fun DashboardActionStrip(
    callbacks: DashboardCallbacks,
    session: SessionSnapshot,
    contentWidth: Dp,
) {
    val actions = callbacks.actions(session)
    val perButton = (contentWidth - DashboardDimens.ButtonGap * (actions.size - 1)) / actions.size
    val rows = if (perButton >= DashboardDimens.TileMinWidth) 1 else 2
    ActionGrid(actions = actions, perRow = (actions.size + rows - 1) / rows)
}

/**
 * Full-width grid of equal-width action buttons, [perRow] abreast. Every button gets the
 * width a FULL row would give it; a partial last row keeps that width and is centred under
 * the rows above (instead of leaving a hole on the end side, which read as off-centre).
 */
@Composable
private fun ActionGrid(
    actions: List<DashboardAction>,
    perRow: Int,
) {
    val columns = perRow.coerceIn(1, actions.size.coerceAtLeast(1))
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val buttonWidth = (maxWidth - DashboardDimens.ButtonGap * (columns - 1)) / columns
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(DashboardDimens.ButtonGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            actions.chunked(columns).forEach { rowActions ->
                Row(horizontalArrangement = Arrangement.spacedBy(DashboardDimens.ButtonGap)) {
                    rowActions.forEach { action ->
                        DashboardActionButton(
                            label = action.label,
                            icon = action.icon,
                            onClick = action.onClick,
                            tone = action.tone,
                            modifier = Modifier.width(buttonWidth),
                        )
                    }
                }
            }
        }
    }
}

// ==================== Logs page ====================

@Composable
private fun LogsPage(
    fileLogManager: FileLogManager?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(AutomotiveDimens.ButtonMinHeight)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to settings",
                    modifier = Modifier.size(AutomotiveDimens.IconSize),
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Logs",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LogsTabContent(context, fileLogManager)
        }
    }
}
