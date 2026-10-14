package com.carlink.ui.components

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.carlink.logging.logDebug
import com.carlink.logging.logWarn
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Downscale factor applied when snapshotting the video surface for the settings backdrop.
 *
 * 8x (2400x960 -> 300x120, ~144 KB ARGB) does two jobs: it keeps the PixelCopy cheap, and —
 * because the bitmap is later drawn back at full size with bilinear filtering — it already
 * reads as a soft blur on its own. That matters on API 29/30 (minSdk 29), where
 * `Modifier.blur` is a documented no-op (RenderEffect is API 31+): the downscale is the only
 * blur those devices get, so don't lower it without checking the backdrop still looks
 * frosted without RenderEffect.
 */
const val SURFACE_SNAPSHOT_DOWNSCALE: Int = 8

/**
 * Capture one frame of [state]'s video [android.view.Surface] via [PixelCopy] as a downscaled
 * [ImageBitmap], or null if the surface isn't ready, the copy fails, or the surface has no
 * buffer yet (PixelCopy.ERROR_SOURCE_NO_DATA — e.g. STREAMING state reached but no frame
 * decoded yet). Suspends until PixelCopy's async completion.
 *
 * Why a snapshot instead of blurring the live view: the video is a SurfaceView on an HWC
 * overlay plane; Compose's `Modifier.blur` (RenderEffect) only affects content drawn by the
 * app's own render thread, so it can't touch the overlay. A static bitmap drawn by Compose
 * CAN be blurred — and a frozen frame is also what the user expects a "paused behind
 * settings" backdrop to look like. Technique mirrors carlink_native_personal's
 * `captureSurfaceBitmap` (verified there on real hardware); constants are this app's own.
 *
 * Failed captures recycle their bitmap immediately instead of leaving a multi-MB object for GC.
 */
suspend fun captureSurfaceBitmap(state: VideoSurfaceState): ImageBitmap? {
    val surface = state.surface ?: return null
    val w = state.width / SURFACE_SNAPSHOT_DOWNSCALE
    val h = state.height / SURFACE_SNAPSHOT_DOWNSCALE
    if (w <= 0 || h <= 0 || !surface.isValid) return null
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val result =
        suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(
                    surface,
                    bitmap,
                    { code -> if (cont.isActive) cont.resume(code) },
                    Handler(Looper.getMainLooper()),
                )
            } catch (e: IllegalArgumentException) {
                // Surface not backed by a usable buffer (rare race when the overlay opens
                // right as the surface is torn down) — degrade to no backdrop, never crash.
                logWarn("[SNAPSHOT] PixelCopy rejected surface: ${e.message}", tag = "UI")
                if (cont.isActive) cont.resume(PixelCopy.ERROR_SOURCE_INVALID)
            }
        }
    if (result != PixelCopy.SUCCESS) {
        logDebug("[SNAPSHOT] PixelCopy failed: code=$result (${w}x$h)", tag = "UI")
        bitmap.recycle()
        return null
    }
    logDebug("[SNAPSHOT] Captured ${w}x$h backdrop", tag = "UI")
    return bitmap.asImageBitmap()
}
