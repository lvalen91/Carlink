package com.carlink.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.carlink.CarlinkManager
import com.carlink.protocol.PhoneType
import kotlinx.coroutines.delay

/**
 * Point-in-time view of the connection-related [CarlinkManager] fields the settings
 * dashboard renders from. Immutable so a single `State<SessionSnapshot>` write per poll
 * tick recomposes only what actually changed.
 */
@Immutable
data class SessionSnapshot(
    val state: CarlinkManager.State,
    val phoneType: PhoneType?,
    /** Adapter-reported transport: 1 = wireless, 0 = USB, -1 = not reported (treated as USB). */
    val wifi: Int,
    val connectedBtMac: String?,
    /** Live one-line phase string ("Searching for box...", "Streaming", ...); "" before start(). */
    val statusText: String,
    /** The WiFi/BT name the adapter advertises — what the dashboard tells the user to connect to. */
    val adapterName: String,
) {
    val isAdapterConnected: Boolean
        get() = state != CarlinkManager.State.DISCONNECTED

    /**
     * wifi=0 means explicit USB; wifi=-1 (null) with an active phoneType means the adapter
     * didn't send the wifi field — treat as USB since wireless always sends wifi=1 explicitly.
     */
    val isUsbPhoneConnected: Boolean
        get() = phoneType != null && wifi != 1

    /** The paired wireless device currently projecting, if any (btMac). */
    val activeWirelessMac: String?
        get() =
            connectedBtMac?.takeIf {
                wifi == 1 &&
                    (state == CarlinkManager.State.STREAMING || state == CarlinkManager.State.DEVICE_CONNECTED)
            }

    companion object {
        fun read(manager: CarlinkManager): SessionSnapshot =
            SessionSnapshot(
                state = manager.state,
                phoneType = manager.currentPhoneType,
                wifi = manager.currentWifi ?: -1,
                connectedBtMac = manager.connectedBtMac,
                statusText = manager.statusText,
                adapterName = manager.adapterName,
            )
    }
}

/**
 * Poll [CarlinkManager] connection state at [intervalMs] while in composition.
 *
 * Rationale (unchanged from the previous Phones tab): CarlinkManager.callback is single-slot
 * and already consumed by MainScreen, and DeviceListener only fires on DevList changes —
 * neither surfaces state/phoneType/wifi/btMac/statusText transitions to secondary observers. A 1 Hz poll
 * is the pragmatic workaround; a multi-observer StateFlow on CarlinkManager is the proper fix
 * and would retire this. One poller now serves the whole dashboard (device list AND the
 * control dialogs' enabled-gating), where previously the Control tab read `manager.state`
 * non-reactively and could show stale enabled states.
 */
@Composable
fun rememberSessionSnapshot(
    manager: CarlinkManager,
    intervalMs: Long = 1_000L,
): State<SessionSnapshot> {
    val snapshot = remember(manager) { mutableStateOf(SessionSnapshot.read(manager)) }
    LaunchedEffect(manager) {
        while (true) {
            val next = SessionSnapshot.read(manager)
            if (next != snapshot.value) snapshot.value = next
            delay(intervalMs)
        }
    }
    return snapshot
}
