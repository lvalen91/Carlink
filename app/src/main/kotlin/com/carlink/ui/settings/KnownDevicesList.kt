package com.carlink.ui.settings

import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.carlink.CarlinkManager
import com.carlink.R
import com.carlink.logging.logWarn
import com.carlink.protocol.PhoneType
import com.carlink.ui.theme.AutomotiveDimens
import com.carlink.util.DebugProps
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

// ==================== Protocol mapping ====================

/**
 * Projection protocol of a known device, resolved ONCE at the UI boundary from either the
 * adapter's free-text DevList `type` or the live [PhoneType].
 *
 * Replaces the previous raw `when (device.type) { "CarPlay" -> ...; "AndroidAuto" -> ... }`
 * matches, which silently fell through to a generic icon/tint on any spelling drift
 * ("Carplay", "CarPlay-W", "Android Auto"). [fromAdapterType] normalises to lowercase
 * alphanumerics and prefix-matches, and logs each unknown spelling once so drift is visible
 * in the logs instead of invisible in the UI. The raw String stays on
 * [CarlinkManager.DeviceInfo.type] for debugging.
 */
enum class DeviceProtocol(
    val label: String,
    @param:DrawableRes val iconRes: Int,
) {
    CARPLAY("CarPlay", R.drawable.ic_carplay),
    ANDROID_AUTO("Android Auto", R.drawable.ic_android_auto),
    HICAR("HiCar", R.drawable.ic_phone_projection),
    UNKNOWN("Phone", R.drawable.ic_phone_projection),
    ;

    companion object {
        private val warnedTypes: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun fromAdapterType(raw: String?): DeviceProtocol {
            val key = raw.orEmpty().lowercase().filter { it.isLetterOrDigit() }
            return when {
                key.startsWith("carplay") -> CARPLAY
                key.startsWith("androidauto") -> ANDROID_AUTO
                key.startsWith("hicar") -> HICAR
                else -> {
                    if (warnedTypes.add(key)) {
                        logWarn("[DEVICES] Unknown adapter device type '$raw' — rendering generic", tag = "UI")
                    }
                    UNKNOWN
                }
            }
        }

        fun fromPhoneType(type: PhoneType?): DeviceProtocol =
            when (type) {
                PhoneType.CARPLAY, PhoneType.CARPLAY_WIRELESS -> CARPLAY
                PhoneType.ANDROID_AUTO -> ANDROID_AUTO
                PhoneType.HI_CAR -> HICAR
                else -> UNKNOWN
            }
    }
}

// Active card background tints (unchanged from the previous Phones tab).
private val CarPlayActiveColor = Color(0xFF1B3A1F) // Dark green tint
private val AndroidAutoActiveColor = Color(0xFF1A2A3D) // Dark blue tint

@Composable
private fun activeCardColor(protocol: DeviceProtocol?): Color =
    when (protocol) {
        DeviceProtocol.CARPLAY -> CarPlayActiveColor
        DeviceProtocol.ANDROID_AUTO -> AndroidAutoActiveColor
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }

// ==================== Item model + ordering ====================

/** One entry in the known-devices list. [key] is stable across reorders for Lazy* keying. */
sealed interface KnownDeviceItem {
    val key: String

    /** The wired slot. Always present; non-interactive (the adapter owns USB sessions). */
    data class Usb(
        val connected: Boolean,
        val protocol: DeviceProtocol?,
    ) : KnownDeviceItem {
        override val key: String get() = "usb"
    }

    /** A paired wireless device from the adapter's DevList. */
    data class Wireless(
        val device: CarlinkManager.DeviceInfo,
        val protocol: DeviceProtocol,
        val connected: Boolean,
    ) : KnownDeviceItem {
        override val key: String get() = "bt:${device.btMac}"
    }

    /** Placeholder shown when the DevList is empty. */
    data object Empty : KnownDeviceItem {
        override val key: String get() = "empty"
    }
}

/**
 * Order the list so the ACTIVE device is first (leftmost in a row / topmost in a column),
 * then the USB slot if it isn't the active one, then the remaining wireless devices in
 * adapter order, then the empty placeholder if there are no wireless devices at all.
 * Pure function so it is unit-testable and shared by both orientations.
 */
fun buildKnownDeviceItems(
    devices: List<CarlinkManager.DeviceInfo>,
    session: SessionSnapshot,
): List<KnownDeviceItem> {
    val usb =
        KnownDeviceItem.Usb(
            connected = session.isUsbPhoneConnected,
            protocol = if (session.isUsbPhoneConnected) DeviceProtocol.fromPhoneType(session.phoneType) else null,
        )
    val activeMac = session.activeWirelessMac
    val wireless =
        devices.map { device ->
            KnownDeviceItem.Wireless(
                device = device,
                protocol = DeviceProtocol.fromAdapterType(device.type),
                connected = activeMac != null && device.btMac == activeMac,
            )
        }
    val (active, inactive) = wireless.partition { it.connected }
    return buildList {
        if (usb.connected) {
            add(usb)
            addAll(active) // defensive: both flags true should be impossible, but never drop a card
        } else {
            addAll(active)
            add(usb)
        }
        addAll(inactive)
        if (wireless.isEmpty()) add(KnownDeviceItem.Empty)
    }
}

// ==================== List composable ====================

/**
 * Known-devices list, orientation-aware: a [LazyRow] of tall tiles when
 * [DashboardLayout.devicesScrollHorizontally], else a plain (non-lazy, non-scrolling) column
 * of full-width row cards. The data, ordering ([buildKnownDeviceItems]) and actions are
 * identical in both; only the container and the card shape change.
 *
 * Sizing contract: this composable wraps its content. In the horizontal case each tile is
 * [tileHeight] tall (clamped to at least [rememberDeviceTileMinHeight] so content never
 * clips); callers that want tiles to fill a pane pass the pane height, callers inside a
 * vertically scrolling column pass null to get the minimum. Tile width is derived from the
 * measured available width via [deviceTileWidth]; the row then takes only the width its tiles
 * need and is centred horizontally in whatever width [modifier] gives this composable (pass
 * `fillMaxWidth()` to centre it in the parent; without it the composable wraps the row).
 * [contentPadding]'s horizontal insets are part of that row width (the tiles scroll through
 * them, so a card behind the row can clip the tiles at its own edge).
 *
 * The vertical case does NOT scroll by itself: the caller puts it inside its own vertically
 * scrolling column together with whatever sits above it (PORTRAIT: the controls card), so
 * everything scrolls as one and a short panel can never squeeze the list to zero height. The
 * adapter's DevList is a handful of entries, so a non-lazy column costs nothing.
 *
 * @param session polled connection snapshot (see [rememberSessionSnapshot]); shared with the
 *   rest of the dashboard so every card and dialog agrees on who is connected.
 * @param header composed directly above the tiles / rows. Horizontal: start-aligned with the
 *   row (which may be narrower than this composable and centred within it) and NOT inset by
 *   [contentPadding] — the caller pads it to line up with the first tile.
 */
@Suppress("LongMethod")
@Composable
fun KnownDevicesList(
    carlinkManager: CarlinkManager,
    session: SessionSnapshot,
    layout: DashboardLayout,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    tileHeight: Dp? = null,
    header: (@Composable () -> Unit)? = null,
) {
    val view = LocalView.current

    var pairedDevices by remember(carlinkManager) { mutableStateOf(carlinkManager.pairedDevices) }

    // Register device listener for DevList updates (supports multiple listeners).
    DisposableEffect(carlinkManager) {
        val listener = CarlinkManager.DeviceListener { devices -> pairedDevices = devices }
        carlinkManager.addDeviceListener(listener)
        // Request fresh device list when the dashboard opens.
        carlinkManager.refreshDeviceList()
        onDispose { carlinkManager.removeDeviceListener(listener) }
    }

    // Guard against rapid card taps launching conflicting connect/disconnect operations.
    // Cleared when the manager settles (STREAMING or DISCONNECTED) — and, new here, by a
    // 10 s watchdog so a swallowed 0x0F / stuck CONNECTING can no longer leave every card
    // disabled indefinitely (the previous Phones tab documented this hazard without a fix).
    var isProcessing by remember(carlinkManager) { mutableStateOf(false) }
    LaunchedEffect(session.state) {
        if (session.state == CarlinkManager.State.STREAMING ||
            session.state == CarlinkManager.State.DISCONNECTED
        ) {
            isProcessing = false
        }
    }
    LaunchedEffect(isProcessing) {
        if (isProcessing) {
            delay(PROCESSING_WATCHDOG_MS)
            isProcessing = false
        }
    }

    // Hoisted remove-dialog state (stable device reference across recompositions).
    var deviceToRemove by remember { mutableStateOf<CarlinkManager.DeviceInfo?>(null) }

    // Debug-only synthetic devices for visual verification on adapter-less emulators.
    val fakes = remember { DebugFakeDevices.read() }
    val effectiveSession = fakes.applyTo(session)
    val items =
        remember(pairedDevices, effectiveSession, fakes) {
            buildKnownDeviceItems(pairedDevices + fakes.devices, effectiveSession)
        }

    val onTapWireless: (KnownDeviceItem.Wireless) -> Unit = { item ->
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        isProcessing = true
        if (item.connected) {
            carlinkManager.disconnectPhone()
        } else {
            carlinkManager.connectToDevice(item.device.btMac)
        }
    }

    if (layout.devicesScrollHorizontally) {
        val minTileHeight = rememberDeviceTileMinHeight()
        val resolvedTileHeight =
            (tileHeight ?: minTileHeight).coerceIn(minTileHeight, maxOf(minTileHeight, DashboardDimens.TileMaxHeight))
        val density = LocalDensity.current
        BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.TopCenter) {
            val leftInset = contentPadding.calculateLeftPadding(LayoutDirection.Ltr)
            val rightInset = contentPadding.calculateRightPadding(LayoutDirection.Ltr)
            val tileWidth = deviceTileWidth(maxWidth - leftInset - rightInset, items.size)
            // The row is only as wide as its tiles (in whole px, exactly as LazyRow will lay
            // them out, so a row that fits never scrolls by a rounding pixel), capped at the
            // available width — when the tiles fit, the group is centred in this box instead
            // of hugging the start edge; when they overflow it fills the width and scrolls.
            val rowWidth =
                with(density) {
                    val neededPx =
                        tileWidth.roundToPx() * items.size +
                            DashboardDimens.Gap.roundToPx() * (items.size - 1).coerceAtLeast(0) +
                            leftInset.roundToPx() + rightInset.roundToPx()
                    neededPx.coerceAtMost(constraints.maxWidth).toDp()
                }
            Column(modifier = Modifier.width(rowWidth)) {
                header?.invoke()
                LazyRow(
                    modifier = Modifier.fillMaxWidth().height(resolvedTileHeight),
                    contentPadding = contentPadding,
                    horizontalArrangement = Arrangement.spacedBy(DashboardDimens.Gap),
                ) {
                    items(items, key = { it.key }) { item ->
                        DeviceTile(
                            item = item,
                            enabled = !isProcessing,
                            onTap = { (item as? KnownDeviceItem.Wireless)?.let(onTapWireless) },
                            onRemove = { (item as? KnownDeviceItem.Wireless)?.let { deviceToRemove = it.device } },
                            modifier = Modifier.width(tileWidth).height(resolvedTileHeight),
                        )
                    }
                }
            }
        }
    } else {
        Column(modifier = modifier.fillMaxWidth().padding(contentPadding)) {
            header?.invoke()
            Column(verticalArrangement = Arrangement.spacedBy(DashboardDimens.ButtonGap)) {
                items.forEach { item ->
                    // key() keeps each row's identity (and its Card's interaction state) across
                    // reorders, as the LazyColumn item keys did.
                    key(item.key) {
                        DeviceRow(
                            item = item,
                            enabled = !isProcessing,
                            onTap = { (item as? KnownDeviceItem.Wireless)?.let(onTapWireless) },
                            onRemove = { (item as? KnownDeviceItem.Wireless)?.let { deviceToRemove = it.device } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    // Hoisted remove confirmation dialog.
    // NOTE: forgetDevice is invoked on the main thread here. CarlinkManager optimistically
    // mutates _deviceList on main while IO-dispatched callbacks may also fire — assumed safe
    // pending audit; no ConcurrentModification observed in POTATO captures. Still warrants an
    // explicit main-thread-only contract on _deviceList.
    deviceToRemove?.let { device ->
        RemoveDeviceDialog(
            deviceName = device.name,
            onConfirm = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                carlinkManager.forgetDevice(device.btMac)
                deviceToRemove = null
            },
            onDismiss = { deviceToRemove = null },
        )
    }
}

private const val PROCESSING_WATCHDOG_MS = 10_000L

/**
 * DEBUG-ONLY visual test hook (no-op in release — see [DebugProps]).
 *
 *   adb shell setprop debug.carlink.fake_devices N[,active]
 *
 * adds N synthetic paired devices (alternating CarPlay / Android Auto, with one deliberately
 * long name to exercise wrapping) to whatever the adapter reports, and with `,active` also
 * pretends the first one is the connected wireless device so the "active first + tinted"
 * ordering can be seen. Read once when the list enters composition (reopen Settings after
 * changing the property). Taps on fake cards reach CarlinkManager like real ones would, which
 * on an adapter-less emulator is harmless. Never set this on a real head unit.
 */
private class DebugFakeDevices(
    val devices: List<CarlinkManager.DeviceInfo>,
    val activeMac: String?,
) {
    fun applyTo(session: SessionSnapshot): SessionSnapshot =
        if (activeMac == null) {
            session
        } else {
            session.copy(
                state = CarlinkManager.State.STREAMING,
                phoneType = PhoneType.CARPLAY_WIRELESS,
                wifi = 1,
                connectedBtMac = activeMac,
            )
        }

    companion object {
        private val NONE = DebugFakeDevices(emptyList(), null)

        fun read(): DebugFakeDevices {
            val raw = DebugProps.get("debug.carlink.fake_devices") ?: return NONE
            val parts = raw.split(',')
            val count = parts.firstOrNull()?.trim()?.toIntOrNull()?.coerceIn(0, 12) ?: return NONE
            if (count == 0) return NONE
            val devices =
                List(count) { i ->
                    val carPlay = i % 2 == 0
                    CarlinkManager.DeviceInfo(
                        btMac = "DE:BU:G0:00:00:%02X".format(i),
                        name =
                            when (i) {
                                0 -> "Test iPhone"
                                1 -> "Pixel 9 Pro"
                                2 -> "A deliberately long device name to exercise wrapping"
                                else -> "Phone ${i + 1}"
                            },
                        type = if (carPlay) "CarPlay" else "AndroidAuto",
                        lastConnected = if (carPlay) "2026-10-12 18:${20 + i}" else null,
                    )
                }
            val active = parts.drop(1).any { it.trim().equals("active", ignoreCase = true) }
            return DebugFakeDevices(devices, if (active) devices.first().btMac else null)
        }
    }
}

private val TilePadding = 20.dp
private val TileIconSize = 48.dp
private val RowPadding = 16.dp
private val RowIconSize = 40.dp
private val RowRemoveButtonSize = 56.dp

/**
 * Minimum tile height that fits the tallest tile content without clipping, derived from the
 * CURRENT typography (so it scales with the user's font size) rather than a hardcoded dp:
 * padding + icon + 2-line name + protocol line + 2-line status + Remove button.
 */
@Composable
fun rememberDeviceTileMinHeight(): Dp {
    val typography = MaterialTheme.typography
    val density = LocalDensity.current
    return remember(typography, density) {
        with(density) {
            TilePadding * 2 +
                TileIconSize + 12.dp +
                typography.titleLarge.lineHeight.toDp() * 2 + 4.dp +
                typography.bodyMedium.lineHeight.toDp() + 4.dp +
                typography.bodySmall.lineHeight.toDp() * 2 + 16.dp +
                AutomotiveDimens.ButtonMinHeight
        }
    }
}

// ==================== Card visuals ====================

/** Presentation values shared by the tile and row renderings of one [KnownDeviceItem]. */
private class DeviceVisual(
    val title: String,
    val protocol: DeviceProtocol?,
    val status: String,
    val connected: Boolean,
    val clickable: Boolean,
    val removable: Boolean,
    val dimmed: Boolean,
)

private fun KnownDeviceItem.visual(): DeviceVisual =
    when (this) {
        is KnownDeviceItem.Usb ->
            DeviceVisual(
                title = "USB",
                protocol = protocol,
                status = if (connected) "Connected" else "No device",
                connected = connected,
                clickable = false,
                removable = false,
                dimmed = !connected,
            )

        is KnownDeviceItem.Wireless ->
            DeviceVisual(
                title = device.name,
                protocol = protocol,
                status =
                    if (connected) {
                        "Connected"
                    } else {
                        device.lastConnected?.let { "Last seen: $it" } ?: "Not connected"
                    },
                connected = connected,
                clickable = true,
                removable = true,
                dimmed = false,
            )

        KnownDeviceItem.Empty ->
            DeviceVisual(
                title = "No paired wireless devices",
                protocol = null,
                status = "Connect a phone to the box to get started",
                connected = false,
                clickable = false,
                removable = false,
                dimmed = true,
            )
    }

@Composable
private fun DeviceGlyph(
    item: KnownDeviceItem,
    visual: DeviceVisual,
    tint: Color,
    size: Dp,
) {
    when (item) {
        is KnownDeviceItem.Usb ->
            Icon(
                imageVector = if (visual.connected) Icons.Default.Usb else Icons.Default.UsbOff,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size),
            )

        is KnownDeviceItem.Wireless ->
            Image(
                painter = painterResource(id = item.protocol.iconRes),
                contentDescription = item.protocol.label,
                modifier = Modifier.size(size),
            )

        KnownDeviceItem.Empty ->
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size),
            )
    }
}

/** Tall tile for the horizontally scrolling list (LANDSCAPE / BOXY). */
@Suppress("LongMethod")
@Composable
private fun DeviceTile(
    item: KnownDeviceItem,
    enabled: Boolean,
    onTap: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val visual = item.visual()
    val containerColor =
        when {
            visual.connected -> activeCardColor(visual.protocol)
            visual.dimmed -> colorScheme.surfaceContainerLow
            else -> colorScheme.surfaceContainer
        }
    val textColor =
        when {
            visual.connected -> Color.White
            visual.dimmed -> colorScheme.onSurface.copy(alpha = 0.38f)
            else -> colorScheme.onSurface
        }
    val secondaryColor = if (visual.connected) Color.White.copy(alpha = 0.85f) else colorScheme.onSurfaceVariant

    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(TilePadding).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DeviceGlyph(item, visual, tint = textColor, size = TileIconSize)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = visual.title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = textColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Protocol line: always reserved (even when blank) so tiles keep a uniform rhythm.
            Text(
                text = visual.protocol?.label ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = secondaryColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = visual.status,
                style = MaterialTheme.typography.bodySmall,
                color = secondaryColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (item is KnownDeviceItem.Usb && item.connected && item.protocol != null) {
                // Wired slot in use: branded protocol badge where a wireless tile has Remove
                // (parity with the previous USB card).
                Spacer(modifier = Modifier.height(16.dp))
                Image(
                    painter = painterResource(id = item.protocol.iconRes),
                    contentDescription = item.protocol.label,
                    modifier = Modifier.size(TileIconSize),
                )
            }
            if (visual.removable) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onRemove,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = AutomotiveDimens.ButtonMinHeight),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = colorScheme.error,
                            contentColor = colorScheme.onError,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Remove", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    if (visual.clickable) {
        Card(
            onClick = onTap,
            enabled = enabled,
            modifier = modifier,
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            colors = CardDefaults.cardColors(containerColor = containerColor, disabledContainerColor = containerColor),
        ) { content() }
    } else {
        Card(
            modifier = modifier,
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            colors = CardDefaults.cardColors(containerColor = containerColor),
        ) { content() }
    }
}

/** Full-width row card for the vertically scrolling list (PORTRAIT). */
@Suppress("LongMethod")
@Composable
private fun DeviceRow(
    item: KnownDeviceItem,
    enabled: Boolean,
    onTap: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val visual = item.visual()
    val containerColor =
        when {
            visual.connected -> activeCardColor(visual.protocol)
            visual.dimmed -> colorScheme.surfaceContainerLow
            else -> colorScheme.surfaceContainer
        }
    val textColor =
        when {
            visual.connected -> Color.White
            visual.dimmed -> colorScheme.onSurface.copy(alpha = 0.38f)
            else -> colorScheme.onSurface
        }
    val secondaryColor = if (visual.connected) Color.White.copy(alpha = 0.85f) else colorScheme.onSurfaceVariant

    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(RowPadding).fillMaxWidth().heightIn(min = RowRemoveButtonSize),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeviceGlyph(item, visual, tint = textColor, size = RowIconSize)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = visual.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = textColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOfNotNull(visual.protocol?.label, visual.status).joinToString(" · ")
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (visual.removable) {
                Spacer(modifier = Modifier.width(12.dp))
                FilledTonalIconButton(
                    onClick = onRemove,
                    enabled = enabled,
                    modifier = Modifier.size(RowRemoveButtonSize),
                    colors =
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = colorScheme.errorContainer,
                            contentColor = colorScheme.onErrorContainer,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove ${visual.title}",
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }

    if (visual.clickable) {
        Card(
            onClick = onTap,
            enabled = enabled,
            modifier = modifier,
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            colors = CardDefaults.cardColors(containerColor = containerColor, disabledContainerColor = containerColor),
        ) { content() }
    } else {
        Card(
            modifier = modifier,
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            colors = CardDefaults.cardColors(containerColor = containerColor),
        ) { content() }
    }
}

// ==================== Dialogs ====================

/**
 * Confirmation dialog for forgetting a paired wireless device. Both dismissal paths
 * (scrim/back and Cancel) route to [onDismiss], which clears the hoisted `deviceToRemove`.
 */
@Composable
private fun RemoveDeviceDialog(
    deviceName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove Device") },
        text = {
            Text(
                "Remove \"$deviceName\" from the box's paired device list? " +
                    "The box will no longer auto-connect to this device.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Remove")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
