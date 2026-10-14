package com.carlink.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.carlink.ui.components.LoadingSpinner
import com.carlink.ui.theme.AutomotiveDimens

/**
 * Which modal (if any) the settings dashboard is currently showing. EXACTLY ONE is ever in
 * composition — the dashboard renders `when (modal)`, so moving from one value to another
 * closes the first window before opening the second.
 *
 * DESIGN DECISION — sequenced, not stacked (see the task brief's open question):
 * The dashboard collapses "Box Configuration" and "App Control" into buttons that open a
 * popup, and each popup has an entry ("Configure" / "Display Mode") that opens a second,
 * much larger dialog. The naive build is two nested `Dialog`s. We deliberately don't do that:
 *
 *  1. Material 3 dialog guidance treats dialogs as purposefully interruptive, single-task
 *     surfaces and advises against a dialog launching another dialog; the one sanctioned
 *     exception is a short confirmation ("Are you sure?") over the task that needs it.
 *  2. Android Automotive design guidance (driver-distraction rules, Car UI library) pushes
 *     shallow task depth and unambiguous back behaviour; two stacked windows give the driver
 *     two scrims, two dismiss targets and a back button whose effect depends on z-order.
 *  3. Each Compose `Dialog` is its own window. Stacking them doubles the scrim, splits
 *     accessibility focus between windows (TalkBack can land in the obscured one), and the
 *     slide-in dashboard animation can't coordinate with window-level dialogs at all.
 *  4. The alternative of expanding "Configure" IN PLACE inside the first popup would force a
 *     compact 3-button sheet to morph into the 600dp-wide multi-section configuration UI,
 *     and would require refactoring `AdapterConfigurationDialog`/`DisplayModeDialog` (which
 *     own window-level preview side effects) out of their `Dialog` wrappers. Nothing inside
 *     those dialogs is supposed to change in this pass.
 *
 * So the popups REPLACE each other: tapping "Configure" closes the actions popup and opens
 * the configuration dialog; dismissing (Cancel / scrim / back) steps back to [parent] — the
 * popup you came from — while committing an action (Apply, Reset, Close) returns to the
 * dashboard because the task is done. The user gets the same one-tap path with never more
 * than one modal on screen and a back button that always means "one step up".
 *
 * The confirmation AlertDialogs that already exist INSIDE `AdapterConfigurationDialog`
 * (Reboot Adapter) are left as-is: a confirmation over its own task is the accepted pattern.
 */
enum class DashboardModal {
    NONE,
    ADAPTER_ACTIONS,
    APP_CONTROL_ACTIONS,
    ADAPTER_CONFIG,
    DISPLAY_MODE,
    RESET_CLUSTER_CONFIRM,
    CLUSTER_NAV_OFF,
    CLOSE_APP_CONFIRM,
    ;

    /** Where a dismiss (Cancel / scrim tap / back) lands. */
    val parent: DashboardModal
        get() =
            when (this) {
                NONE, ADAPTER_ACTIONS, APP_CONTROL_ACTIONS -> NONE
                ADAPTER_CONFIG -> ADAPTER_ACTIONS
                DISPLAY_MODE, RESET_CLUSTER_CONFIRM, CLUSTER_NAV_OFF, CLOSE_APP_CONFIRM -> APP_CONTROL_ACTIONS
            }
}

/**
 * Visual severity class for [ControlButton]. Drives container/content colors only — does not
 * affect behavior or confirmation flow (callers still own any "are you sure?" dialogs).
 */
internal enum class ButtonSeverity {
    WARNING, // Warning action (tertiary/amber)
    DESTRUCTIVE, // Destructive action (error/red)
}

/**
 * Compact action popup: titled surface holding the exact button set the former inline card
 * held. Width tracks the window (60%, clamped) like the existing configuration dialogs so
 * all modals in the app share one footprint; content scrolls if a short panel can't fit it.
 */
@Composable
internal fun ActionDialog(
    title: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val containerWidthDp = with(density) { windowInfo.containerSize.width.toDp() }
    val dialogMaxWidth = (containerWidthDp * 0.6f).coerceIn(280.dp, 480.dp)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(max = dialogMaxWidth).fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                content()
            }
        }
    }
}

/**
 * "Box Configuration" popup — the former inline card's content, verbatim:
 * Configure / Disconnect Phone / Disconnect Adapter.
 */
@Composable
internal fun AdapterActionsDialog(
    session: SessionSnapshot,
    isProcessing: Boolean,
    onConfigure: () -> Unit,
    onDisconnectPhone: () -> Unit,
    onDisconnectAdapter: () -> Unit,
    onDismiss: () -> Unit,
) {
    ActionDialog(
        title = "Box Configuration",
        icon = Icons.Default.SettingsInputComponent,
        onDismiss = onDismiss,
    ) {
        PrimaryActionButton(
            label = "Configure",
            icon = Icons.Default.Settings,
            contentDescription = "Configure box",
            onClick = onConfigure,
        )
        Spacer(modifier = Modifier.height(DashboardDimens.ButtonGap))
        ControlButton(
            label = "Disconnect Phone",
            icon = Icons.Default.PhoneDisabled,
            severity = ButtonSeverity.WARNING,
            enabled = session.isAdapterConnected && !isProcessing,
            isProcessing = isProcessing,
            onClick = onDisconnectPhone,
        )
        Spacer(modifier = Modifier.height(DashboardDimens.ButtonGap))
        ControlButton(
            label = "Disconnect Box",
            icon = Icons.Default.PowerOff,
            severity = ButtonSeverity.DESTRUCTIVE,
            enabled = session.isAdapterConnected && !isProcessing,
            isProcessing = isProcessing,
            onClick = onDisconnectAdapter,
        )
    }
}

/**
 * "App Control" popup: Display Mode / Reset Decoder / Reset Cluster / Close App.
 * (Reset Connection moved out to the dashboard as "Reset Box"; Close App moved in, and its
 * confirmation dialog steps back here on Cancel — see [DashboardModal.parent].)
 *
 * KNOWN UX LIMITATION (carried over): `isProcessing` is one shared flag. Nothing in this popup
 * raises it any more — only the dashboard's Reset Box does, on its in-manager restart fallback —
 * but every button here still gates on it, so while that reset runs they all show the spinner
 * and disable. Per-action flags would fix it; out of scope here.
 */
@Composable
internal fun AppControlActionsDialog(
    currentDisplayMode: DisplayMode,
    isProcessing: Boolean,
    onDisplayMode: () -> Unit,
    onResetDecoder: () -> Unit,
    onResetCluster: () -> Unit,
    onCloseApp: () -> Unit,
    onDismiss: () -> Unit,
) {
    ActionDialog(
        title = "App Control",
        icon = Icons.Default.DisplaySettings,
        onDismiss = onDismiss,
    ) {
        PrimaryActionButton(
            label = "Display Mode",
            icon =
                when (currentDisplayMode) {
                    DisplayMode.SYSTEM_UI_VISIBLE -> Icons.Default.FullscreenExit
                    DisplayMode.STATUS_BAR_HIDDEN -> Icons.Default.Layers
                    DisplayMode.NAV_BAR_HIDDEN -> Icons.Default.WebAsset
                    DisplayMode.FULLSCREEN_IMMERSIVE -> Icons.Default.Fullscreen
                },
            contentDescription = "Configure display mode",
            onClick = onDisplayMode,
        )
        Spacer(modifier = Modifier.height(DashboardDimens.ButtonGap))

        // Reset Decoder + Reset Cluster side by side when the popup is wide enough for two
        // labels without wrapping; stacked otherwise (compact portrait panels).
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val sideBySide = maxWidth >= 400.dp
            if (sideBySide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DashboardDimens.ButtonGap),
                ) {
                    ControlButton(
                        label = "Reset Decoder",
                        icon = Icons.Default.VideoSettings,
                        severity = ButtonSeverity.WARNING,
                        enabled = !isProcessing,
                        isProcessing = isProcessing,
                        onClick = onResetDecoder,
                        modifier = Modifier.weight(1f),
                    )
                    ControlButton(
                        label = "Reset Cluster",
                        icon = Icons.Default.Speed,
                        severity = ButtonSeverity.WARNING,
                        enabled = !isProcessing,
                        isProcessing = isProcessing,
                        onClick = onResetCluster,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(DashboardDimens.ButtonGap)) {
                    ControlButton(
                        label = "Reset Decoder",
                        icon = Icons.Default.VideoSettings,
                        severity = ButtonSeverity.WARNING,
                        enabled = !isProcessing,
                        isProcessing = isProcessing,
                        onClick = onResetDecoder,
                    )
                    ControlButton(
                        label = "Reset Cluster",
                        icon = Icons.Default.Speed,
                        severity = ButtonSeverity.WARNING,
                        enabled = !isProcessing,
                        isProcessing = isProcessing,
                        onClick = onResetCluster,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(DashboardDimens.ButtonGap))
        ControlButton(
            label = "Close App",
            icon = Icons.Default.Close,
            severity = ButtonSeverity.DESTRUCTIVE,
            enabled = !isProcessing,
            isProcessing = isProcessing,
            onClick = onCloseApp,
        )
    }
}

/** Neutral (tonal) full-width action; the "opens the next dialog" entry in each popup. */
@Composable
private fun PrimaryActionButton(
    label: String,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = AutomotiveDimens.ButtonMinHeight),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(AutomotiveDimens.IconSize),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * Material 3 action button for the control popups. Swaps its leading icon for a
 * [LoadingSpinner] while [isProcessing] is true, with a fade+scale transition.
 *
 * Colors come from [severity]: DESTRUCTIVE uses the error container (solid [Button]);
 * WARNING uses the tertiary container ([FilledTonalButton]). Fills the available width and
 * uses [AutomotiveDimens.ButtonMinHeight] as a MINIMUM (not a fixed height) so a label that
 * has to wrap on a narrow panel grows the button instead of clipping.
 *
 * Caller contract:
 *   - [enabled] should already factor in any app-level gating (connection state, etc.);
 *     the `enabled && !isProcessing` below is belt-and-suspenders for callers that forget.
 *   - [isProcessing] controls only the visual swap — the caller owns the flag's lifecycle.
 */
@Suppress("LongMethod", "LongParameterList")
@Composable
internal fun ControlButton(
    label: String,
    icon: ImageVector,
    severity: ButtonSeverity,
    enabled: Boolean,
    isProcessing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val spinnerColor =
        when (severity) {
            ButtonSeverity.DESTRUCTIVE -> colorScheme.onError
            ButtonSeverity.WARNING -> colorScheme.onTertiaryContainer
        }
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        AnimatedContent(
            targetState = isProcessing,
            transitionSpec = { (fadeIn() + scaleIn()).togetherWith(fadeOut() + scaleOut()) },
            label = "iconTransition",
        ) { processing ->
            if (processing) {
                LoadingSpinner(size = 24.dp, color = spinnerColor)
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
    val buttonModifier = modifier.fillMaxWidth().heightIn(min = AutomotiveDimens.ButtonMinHeight)
    val padding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)

    when (severity) {
        ButtonSeverity.DESTRUCTIVE ->
            Button(
                onClick = onClick,
                enabled = enabled && !isProcessing,
                modifier = buttonModifier,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = colorScheme.error,
                        contentColor = colorScheme.onError,
                    ),
                contentPadding = padding,
                content = content,
            )

        ButtonSeverity.WARNING ->
            FilledTonalButton(
                onClick = onClick,
                enabled = enabled && !isProcessing,
                modifier = buttonModifier,
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = colorScheme.tertiaryContainer,
                        contentColor = colorScheme.onTertiaryContainer,
                    ),
                contentPadding = padding,
                content = content,
            )
    }
}
