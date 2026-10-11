package com.carlink.ui.components

import android.widget.ImageView
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.carlink.R
import kotlinx.coroutines.delay

/** Steps per revolution and tick period: 8 x 250 ms = one turn every 2 s, redrawn at 4 fps. */
private const val SPINNER_STEPS = 8
private const val SPINNER_STEP_MS = 250L
private const val SPINNER_STEP_DEGREES = 360f / SPINNER_STEPS

/**
 * Low-rate "ticking" loading spinner: a static vector arc ([R.drawable.ic_spinner_arc]) whose
 * View rotation is stepped 45 degrees every 250 ms from a [LaunchedEffect].
 *
 * WHY NOT the AnimatedVectorDrawable it replaced: the infinite 1 s rotation animator
 * invalidated the ImageView on EVERY vsync, which produced a 60 fps HWUI + SurfaceFlinger
 * frame of the whole 2400x960 window for as long as the "Searching for adapter" / "Waiting for
 * phone" overlays were up — 30 s to minutes at every ignition, ~10% of a core on gminfo37
 * (measured on the realk emulator, `dumpsys gfxinfo`: 601 frames / 10 s while idle), exactly
 * while GM's own boot-time work is busiest. Stepping a RenderNode property (View.rotation)
 * at 4 fps keeps the same native drawable path (no Compose canvas work) and cuts the window
 * redraw rate 15x; a property change does not re-record the view's display list.
 *
 * Lifecycle: the LaunchedEffect is cancelled when this composable leaves composition, so the
 * spinner stops ticking the moment the overlay goes away (the old AVD kept animating until GC).
 *
 * Hard dependency: [R.drawable.ic_spinner_arc] must exist and be a VectorDrawable; the `?.let`
 * below fails silently (blank spinner, no log) if the shrinker strips it or it is renamed.
 * `mutate()` gives this ImageView its own ConstantState so [DrawableCompat.setTint] can't
 * cross-tint other users of the same resource.
 *
 * Default [color] is [MaterialTheme.colorScheme.primary] (resolved at call site, not lazily).
 * Param name [size] intentionally shadows the [Modifier.size] import within this function.
 */
@Composable
fun LoadingSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    // Captured outside factory because AndroidView.factory runs once; color changes flow
    // through the update block below (which re-reads colorArgb via closure on recomposition).
    val colorArgb = color.toArgb()

    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(SPINNER_STEP_MS)
            step = (step + 1) % SPINNER_STEPS
        }
    }

    AndroidView(
        modifier = modifier.size(size),
        factory = { context ->
            ImageView(context).apply {
                ContextCompat.getDrawable(context, R.drawable.ic_spinner_arc)?.mutate()?.let { arc ->
                    DrawableCompat.setTint(arc, colorArgb)
                    setImageDrawable(arc)
                }
            }
        },
        update = { imageView ->
            // Re-runs when colorArgb or `step` changes (AndroidView observes state reads here
            // without recomposing the parent). Redundant setTint is cheap; the rotation write
            // is a RenderNode property update — no display-list re-record.
            imageView.drawable?.let { DrawableCompat.setTint(it, colorArgb) }
            imageView.rotation = step * SPINNER_STEP_DEGREES
        },
    )
}
