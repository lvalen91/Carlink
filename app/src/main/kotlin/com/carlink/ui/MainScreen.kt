package com.carlink.ui

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.carlink.BuildConfig
import com.carlink.CarlinkManager
import com.carlink.logging.logDebug
import com.carlink.logging.logInfo
import com.carlink.protocol.MessageSerializer
import com.carlink.protocol.MultiTouchAction
import com.carlink.protocol.PhoneType
import com.carlink.ui.components.VideoSurface
import com.carlink.ui.components.VideoSurfaceState
import com.carlink.ui.components.rememberVideoSurfaceState
import com.carlink.ui.settings.DisplayMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Projection screen: the H.264 video SurfaceView (HWC overlay) plus touch forwarding, and the
 * place where [CarlinkManager] is initialized against the Surface and started.
 *
 * This composable has NO idle presentation of its own any more. Since the dashboard became the
 * app's base UI (see CarlinkApp in MainActivity.kt), this screen is only ever visible while a
 * session is STREAMING; whenever it isn't, the dashboard is drawn over it. The former loading
 * overlay (spinner, status text, Settings / Reset Device buttons) was dead under that model and
 * was removed — its two actions live on the dashboard (the top-level "Reset Box" button) and the
 * dashboard IS the settings UI.
 *
 * MainScreen must stay composed for the whole life of its [carlinkManager] regardless of
 * visibility: disposing it destroys the Surface under a running MediaCodec ("BufferQueue has
 * been abandoned").
 *
 * @param onConnectionStateChanged Mirrors every [CarlinkManager.Callback.onStateChanged] to the
 *   caller, which decides whether this screen is shown. May be invoked off the main thread
 *   (Compose snapshot-state writes are safe there; see CarlinkManager.setState).
 * @param onHostUiRequested The phone sent REQUEST_HOST_UI (CarPlay's "car" button / AA's exit to
 *   host). The caller reveals the dashboard; the session stays alive underneath.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
fun MainScreen(
    carlinkManager: CarlinkManager,
    displayMode: DisplayMode,
    onConnectionStateChanged: (CarlinkManager.State) -> Unit = {},
    onHostUiRequested: () -> Unit = {},
    // Hoistable so CarlinkApp can PixelCopy the live surface for the dashboard backdrop
    // (see SurfaceSnapshot.kt). Defaults to a local holder for callers that don't need it.
    surfaceState: VideoSurfaceState = rememberVideoSurfaceState(),
) {
    // The CarlinkManager.Callback below is built inside a LaunchedEffect and closes over
    // whatever lambdas were current when the effect (re)launched. Route through
    // rememberUpdatedState so a caller passing a fresh lambda on recomposition is honoured.
    val currentOnConnectionStateChanged by rememberUpdatedState(onConnectionStateChanged)
    val currentOnHostUiRequested by rememberUpdatedState(onHostUiRequested)

    // Key state on carlinkManager identity — when manager is replaced (display mode reinit),
    // all session-scoped state resets automatically. This prevents stale callbacks, flags,
    // or touch state from the old manager leaking into the new session.
    var connectionState by remember(carlinkManager) { mutableStateOf(CarlinkManager.State.DISCONNECTED) }
    var isAndroidAuto by remember(carlinkManager) { mutableStateOf(false) }
    var aaCropParams by remember(carlinkManager) { mutableStateOf<CarlinkManager.AaCropParams?>(null) }

    LaunchedEffect(connectionState) {
        logInfo("[UI_STATE] MainScreen connection state: $connectionState", tag = "UI")
    }

    // Forward the AAOS day/night state to CarPlay. Fires when a session reaches STREAMING (initial
    // sync) and whenever the system theme toggles mid-session (isSystemInDarkTheme recomposes
    // because MainActivity handles the uiMode config change without recreating).
    // systemNight drives ONLY the phone-bound night-mode command — it must never feed
    // CarlinkTheme(darkTheme = ...); this app's own theme stays permanently dark (see Theme.kt).
    val systemNight = isSystemInDarkTheme()
    LaunchedEffect(systemNight, connectionState) {
        if (connectionState == CarlinkManager.State.STREAMING) {
            carlinkManager.setNightMode(systemNight)
        }
    }

    var lastTouchTime by remember(carlinkManager) { mutableLongStateOf(0L) }
    val activeTouches = remember(carlinkManager) { mutableStateMapOf<Int, TouchPoint>() }
    var hasStartedConnection by remember(carlinkManager) { mutableStateOf(false) }

    // Container dimensions (display-mode-padded area) — used for BoxSettings AR calculation.
    // Tracked separately from surfaceState because AA oversizes the SurfaceView beyond the container.
    // NOTE: activeTouches (L95) may leak stale DOWN entries if the SurfaceView is destroyed
    // mid-gesture (AA resize) without a matching UP; bounded-minor leak cleared on next manager
    // reinit (remember(carlinkManager) resets the map).
    var containerSize by remember(carlinkManager) { mutableStateOf(IntSize.Zero) }

    // Handle surface initialization for adapter — uses CONTAINER dimensions for config/BoxSettings,
    // not the SurfaceView dimensions (oversized for AA bar cropping — confirmed in AutoKit; not
    // exercised in 2026-04-20 POTATO gminfo37 captures since all sessions ran CarPlay isAA=false).
    // Re-invoked on every Surface change (SurfaceView destroy→create, AA oversize resize, rotation);
    // CarlinkManager.initialize MUST be idempotent. hasStartedConnection (L96) is cleared only on
    // manager swap, so a new Surface with the same manager (AA resize) will re-initialize but will
    // NOT re-invoke start() — preserving the session.
    LaunchedEffect(surfaceState.surface, containerSize) {
        surfaceState.surface?.let { surface ->
            if (containerSize.width <= 0 || containerSize.height <= 0) return@let

            // Force even dimensions for H.264 macroblock alignment
            val adapterWidth = containerSize.width and 1.inv()
            val adapterHeight = containerSize.height and 1.inv()

            logInfo(
                "[CARLINK_RESOLUTION] Container size: ${adapterWidth}x$adapterHeight " +
                    "(surface: ${surfaceState.width}x${surfaceState.height}, mode=$displayMode)",
                tag = "UI",
            )

            carlinkManager.initialize(
                surface = surface,
                surfaceWidth = adapterWidth,
                surfaceHeight = adapterHeight,
                callback =
                    object : CarlinkManager.Callback {
                        override fun onStateChanged(state: CarlinkManager.State) {
                            connectionState = state
                            currentOnConnectionStateChanged(state)
                        }

                        override fun onStatusTextChanged(text: String) {
                            // No on-screen consumer since the loading overlay went away (the
                            // dashboard renders connection state from SessionSnapshot). The
                            // manager does not log these itself — it only mirrors them into the
                            // MediaSession placeholder — so keep the trail in the UI log.
                            logInfo("[UI_STATE] Adapter status: $text", tag = "UI")
                        }

                        override fun onHostUIPressed() {
                            currentOnHostUiRequested()
                        }

                        override fun onPhoneTypeChanged(phoneType: PhoneType) {
                            // Known limitation: aaCropParams is only refreshed on phoneType transition.
                            // If CarlinkManager later recomputes crop params (e.g. mid-session display
                            // resize), this UI won't see the update until the next phoneType toggle or
                            // manager reinit.
                            val isAA = phoneType == PhoneType.ANDROID_AUTO
                            if (isAA != isAndroidAuto) {
                                isAndroidAuto = isAA
                                aaCropParams = if (isAA) carlinkManager.getAaCropParams() else null
                                logInfo(
                                    "[UI_SURFACE] Phone type changed: $phoneType, isAA=$isAA, crop=$aaCropParams",
                                    tag = "UI",
                                )
                            }
                        }
                    },
            )
            if (!hasStartedConnection) {
                hasStartedConnection = true
                // start() does blocking USB I/O, AudioTrack setup and a 200 ms Thread.sleep;
                // keep it off the main thread like every other caller (restart, reconnect,
                // MainActivity.onNewIntent). Its UI-facing side effects are callback writes to
                // Compose snapshot state and MediaSessionManager calls, both safe off-main.
                withContext(Dispatchers.IO) { carlinkManager.start() }
            }
        }
    }

    val baseModifier = Modifier.fillMaxSize().background(Color.Black)
    val boxModifier =
        when (displayMode) {
            DisplayMode.FULLSCREEN_IMMERSIVE -> {
                baseModifier
            }

            DisplayMode.SYSTEM_UI_VISIBLE -> {
                baseModifier
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .windowInsetsPadding(WindowInsets.displayCutout)
            }

            DisplayMode.STATUS_BAR_HIDDEN,
            DisplayMode.NAV_BAR_HIDDEN,
            -> {
                baseModifier
                    .windowInsetsPadding(WindowInsets.systemBars)
            }
            // No displayCutout padding — video extends behind cutout.
            // (SafeArea, which tells CarPlay where to avoid placing UI, is NOT sent from this file —
            // it is emitted by CarlinkManager/MessageSerializer. This comment documents the visual
            // intent of the hidden-bars branch above, not an action performed here.)
        }

    Box(modifier = boxModifier) {
        val density = LocalDensity.current

        // For AA: SurfaceView oversized to tier AR (16:9), centered + clipped.
        // Black bars in the codec frame fall outside the clip → cropped visually.
        // Codec start is deferred until phone type is known, so surface resize
        // (destroyed→created at oversized dims) happens before decoder is active.
        // (Cross-file: the deferral is enforced in CarlinkManager.kt — see initialize()
        // and the phone-type gate around the codec start path, approx. :247 / :1153 / :1477.)
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().clipToBounds(),
        ) {
            // Track container dimensions for BoxSettings AR calculation
            val containerPx =
                with(density) {
                    IntSize(maxWidth.roundToPx(), maxHeight.roundToPx())
                }
            LaunchedEffect(containerPx) {
                if (containerPx.width > 0 && containerPx.height > 0) {
                    containerSize = containerPx
                }
            }

            // AA: oversize SurfaceView to tier AR so the phone's baked black bars overflow the
            // clip region. Two-way fit puts bars on exactly ONE axis: cropTop>0 → bars top/bottom
            // (oversize height); cropLeft>0 → bars left/right (oversize width). CarPlay fills.
            val surfaceModifier =
                if (aaCropParams != null) {
                    val cp = aaCropParams!!
                    val tierAR = cp.tierWidth.toFloat() / cp.tierHeight.toFloat()
                    if (cp.cropLeft > 0) {
                        val oversizedWidthDp = with(density) { (maxHeight.toPx() * tierAR).toInt().toDp() }
                        Modifier
                            .fillMaxHeight()
                            .requiredWidth(oversizedWidthDp)
                            .align(Alignment.Center)
                    } else {
                        val oversizedHeightDp = with(density) { (maxWidth.toPx() / tierAR).toInt().toDp() }
                        Modifier
                            .fillMaxWidth()
                            .requiredHeight(oversizedHeightDp)
                            .align(Alignment.Center)
                    }
                } else {
                    Modifier.fillMaxSize()
                }

            // Key the VideoSurface on (manager identity, displayMode) so any reinit
            // path (display-mode change, resolution change, dashboard Reset Box via the
            // manager-rebuild path) drops the AndroidView composition slot, disposing
            // the old VideoSurfaceView and releasing its HWC overlay plane. A fresh
            // SurfaceView is then inflated against the current window rect — the only
            // reliable way to migrate the HWC plane when system insets change post-boot.
            // Parent-size deltas alone are NOT included here: they would rebuild the
            // SurfaceView on every transient resize (e.g. bar animation frames) and
            // cause video flicker. Only identity-level changes force recreation.
            key(carlinkManager, displayMode) {
            VideoSurface(
                modifier = surfaceModifier,
                onSurfaceAvailable = { surface, width, height ->
                    logInfo("[UI_SURFACE] Surface available: ${width}x$height (isAA=$isAndroidAuto)", tag = "UI")
                    surfaceState.onSurfaceAvailable(surface, width, height)
                },
                onSurfaceDestroyed = {
                    logInfo("[UI_SURFACE] Surface destroyed", tag = "UI")
                    surfaceState.onSurfaceDestroyed()
                    carlinkManager.onSurfaceDestroyed()
                },
                onSurfaceSizeChanged = { width, height ->
                    logInfo("[UI_SURFACE] Surface size changed: ${width}x$height", tag = "UI")
                    surfaceState.onSurfaceSizeChanged(width, height)
                },
                onTouchEvent = { event ->
                    // Read state directly through the Compose delegate — NOT via
                    // the pre-computed val `isUserInteractingWithProjection`.
                    // This lambda is captured once in AndroidView's factory block;
                    // a pre-computed val would snapshot DISCONNECTED permanently.
                    if (connectionState == CarlinkManager.State.STREAMING) {
                        if (BuildConfig.DEBUG) {
                            val now = System.currentTimeMillis()
                            if (now - lastTouchTime > 1000) {
                                logDebug(
                                    "[UI_TOUCH] touch: action=${event.actionMasked}" +
                                        ", pointers=${event.pointerCount}" +
                                        ", surface=${surfaceState.width}x${surfaceState.height}" +
                                        ", container=${containerSize.width}x${containerSize.height}",
                                    tag = "UI",
                                )
                                lastTouchTime = now
                            }
                        }
                        handleTouchEvent(
                            event,
                            activeTouches,
                            carlinkManager,
                            surfaceState.width,
                            surfaceState.height,
                            containerSize.width,
                            containerSize.height,
                        )
                    }
                    true
                },
            )
            } // end key(carlinkManager, displayMode)
        }
    }
}

/** In-memory per-pointer touch record (normalized 0..1 coords + last action) for deduping MOVE spam. */
private data class TouchPoint(
    val x: Float,
    val y: Float,
    val action: MultiTouchAction,
)

/**
 * Handle touch on the SurfaceView.
 *
 * eventX/Y are in the SurfaceView's coordinate space. For AA the SurfaceView is
 * oversized (e.g. 2400x1350) but only [containerHeight] pixels are visible
 * (the center portion). We subtract the crop offset so touch maps to 0..1 of
 * the visible content area.
 *
 * AA:     type 0x05, 0..10000 ints  (CarlinkManager converts 0..1 → 0..10000)
 * CarPlay: type 0x17, 0..1 floats   (unchanged)
 *
 * Multitouch -> adapter touch protocol translation on the input path; params are the surface/
 * container geometry, the condition the zero-size guard, nesting the per-pointer handling.
 */
@Suppress("CyclomaticComplexMethod", "LongParameterList", "NestedBlockDepth", "ComplexCondition")
private fun handleTouchEvent(
    event: MotionEvent,
    activeTouches: MutableMap<Int, TouchPoint>,
    carlinkManager: CarlinkManager,
    surfaceWidth: Int,
    surfaceHeight: Int,
    containerWidth: Int,
    containerHeight: Int,
) {
    if (surfaceWidth == 0 || surfaceHeight == 0 || containerWidth == 0 || containerHeight == 0) return

    val pointerIndex = event.actionIndex
    val pointerId = event.getPointerId(pointerIndex)

    val action =
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> MultiTouchAction.DOWN
            MotionEvent.ACTION_MOVE -> MultiTouchAction.MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> MultiTouchAction.UP
            else -> return
        }

    // Crop offset: how many SurfaceView pixels are hidden outside the visible area.
    // Two-way fit oversizes exactly ONE axis, so one offset is >0 and the other is 0:
    //   vertical oversize   → surfaceHeight > containerHeight → cropTopY  > 0, cropLeftX = 0
    //   horizontal oversize → surfaceWidth  > containerWidth  → cropLeftX > 0, cropTopY  = 0
    // CarPlay (no oversize): both 0. (POTATO 2026-04-20 shows harmless off-by-1 drift between
    // container and surface dims, inside MotionEvent rounding.)
    val cropTopY = (surfaceHeight - containerHeight) / 2f
    val cropLeftX = (surfaceWidth - containerWidth) / 2f

    // Normalize to 0..1 of the OVERSIZED surface (matches AutoKit's scaled_width/scaled_height
    // denominators): subtract the crop offset on the oversized axis, divide by the surface dim.
    // Confirmed against AutoKit: its AA touch path uses `(x * 10000) / scaled_width` and
    // `(y * 10000) / scaled_height`, where scaled_* are the OVERSIZED post-resize view dims (not
    // the visible content box). The visible area then normalizes to 0..(container/surface) on the
    // oversized axis; AA side scales 0..1 → 0..10000 at CarlinkManager.kt (sendMultiTouch).
    val x = (event.getX(pointerIndex) - cropLeftX) / surfaceWidth
    val y = (event.getY(pointerIndex) - cropTopY) / surfaceHeight

    when (action) {
        MultiTouchAction.DOWN -> {
            activeTouches[pointerId] = TouchPoint(x, y, action)
        }

        MultiTouchAction.MOVE -> {
            for (i in 0 until event.pointerCount) {
                val id = event.getPointerId(i)
                val px = (event.getX(i) - cropLeftX) / surfaceWidth
                val py = (event.getY(i) - cropTopY) / surfaceHeight
                activeTouches[id]?.let { existing ->
                    // Deadband: delta is normalized 0..1, multiplied by 1000 then compared against 3.
                    // Effective threshold ≈ 0.003 of the normalized surface (~0.3%), i.e. ~30 units
                    // on AA's 0..10000 scale, ~7 px horizontal / ~4 px vertical on a 2400x1350 AA
                    // surface. Suppresses sub-pixel MOVE spam without losing real drags.
                    val dx = kotlin.math.abs(existing.x - px) * 1000
                    val dy = kotlin.math.abs(existing.y - py) * 1000
                    if (dx > 3 || dy > 3) activeTouches[id] = TouchPoint(px, py, MultiTouchAction.MOVE)
                }
            }
        }

        MultiTouchAction.UP -> {
            activeTouches[pointerId] = TouchPoint(x, y, action)
        }

        // Dead / defensive: the when at L447-451 only yields DOWN/MOVE/UP (other MotionEvent
        // actions early-return). This branch exists to satisfy exhaustiveness and guard against
        // future MultiTouchAction additions. Intentionally a no-op.
        else -> {}
    }

    val touchList =
        activeTouches.entries.map { entry ->
            MessageSerializer.TouchPoint(
                x = entry.value.x,
                y = entry.value.y,
                action = entry.value.action,
                id = entry.key,
            )
        }

    carlinkManager.sendMultiTouch(touchList)
    activeTouches.entries.removeIf { it.value.action == MultiTouchAction.UP }
}
