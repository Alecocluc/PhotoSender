package com.appharbor.pherry.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.appharbor.pherry.ui.theme.PherryShape
import com.appharbor.pherry.ui.theme.PherryTheme
import com.appharbor.pherry.ui.theme.Spacing

// ── Buttons ────────────────────────────────────────────────────────────────

enum class PrintButtonStyle { Ink, Outline, Quiet, Danger, DangerOutline }

/**
 * Pherry's button. Filled buttons print in ink; on the envelope ([onEnvelope]) they print in the
 * envelope's own ink. Always at least 48dp tall.
 */
@Composable
fun PrintButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PrintButtonStyle = PrintButtonStyle.Ink,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    onEnvelope: Boolean = false,
) {
    val c = PherryTheme.colors
    val ink = if (onEnvelope) c.onEnvelope else c.ink
    val paper = if (onEnvelope) c.envelope else c.paper
    val content: @Composable () -> Unit = {
        if (icon != null) {
            PhIcon(icon, contentDescription = null, size = 18.dp)
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val padding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
    val m = modifier.heightIn(min = Spacing.touch)
    when (style) {
        PrintButtonStyle.Ink, PrintButtonStyle.Danger -> Button(
            onClick = onClick,
            enabled = enabled,
            modifier = m,
            shape = PherryShape.button,
            contentPadding = padding,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (style == PrintButtonStyle.Danger) c.red else ink,
                contentColor = if (style == PrintButtonStyle.Danger) MaterialTheme.colorScheme.onError else paper,
                disabledContainerColor = ink.copy(alpha = 0.18f),
                disabledContentColor = ink.copy(alpha = 0.5f),
            ),
        ) { content() }
        PrintButtonStyle.Outline, PrintButtonStyle.DangerOutline -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = m,
            shape = PherryShape.button,
            contentPadding = padding,
            border = BorderStroke(1.5.dp, if (style == PrintButtonStyle.DangerOutline) c.red else ink.copy(alpha = if (enabled) 1f else 0.3f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (style == PrintButtonStyle.DangerOutline) c.red else ink,
                disabledContentColor = ink.copy(alpha = 0.4f),
            ),
        ) { content() }
        PrintButtonStyle.Quiet -> TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = m,
            shape = PherryShape.button,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = ink, disabledContentColor = ink.copy(alpha = 0.4f)),
        ) { content() }
    }
}

// ── Lamps ──────────────────────────────────────────────────────────────────

enum class LampState { On, Busy, Off, Idle }

/** Status lamp: green when linked, blinking yellow while working, red when broken, hollow when idle. */
@Composable
fun Lamp(state: LampState, modifier: Modifier = Modifier, onEnvelope: Boolean = false) {
    // Only a busy lamp animates; a steady one must not keep a frame clock running.
    if (state == LampState.Busy) {
        BusyLamp(modifier, onEnvelope)
    } else {
        SteadyLamp(state, modifier, onEnvelope)
    }
}

@Composable
private fun SteadyLamp(state: LampState, modifier: Modifier, onEnvelope: Boolean) {
    val c = PherryTheme.colors
    Canvas(modifier.size(10.dp)) {
        val r = size.minDimension / 2
        when (state) {
            LampState.On -> if (onEnvelope) {
                // Green alone is too faint on yellow: ring it in the envelope's ink.
                drawCircle(c.green, radius = r)
                drawCircle(c.onEnvelope, radius = r - 0.6.dp.toPx(), style = Stroke(1.2.dp.toPx()))
            } else {
                drawCircle(c.green.copy(alpha = 0.22f), radius = r * 1.6f)
                drawCircle(c.green, radius = r)
            }
            LampState.Off -> drawCircle(c.red, radius = r)
            LampState.Idle -> drawCircle(
                if (onEnvelope) c.onEnvelope2 else c.ink3,
                radius = r - 0.75.dp.toPx(),
                style = Stroke(1.5.dp.toPx()),
            )
            LampState.Busy -> Unit
        }
    }
}

@Composable
private fun BusyLamp(modifier: Modifier, onEnvelope: Boolean) {
    val c = PherryTheme.colors
    val blink = rememberInfiniteTransition(label = "lamp").animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "lamp-blink",
    )
    Canvas(modifier.size(10.dp)) {
        val r = size.minDimension / 2
        // Read in the draw phase so blinking only redraws, never recomposes.
        val a = blink.value
        if (onEnvelope) {
            drawCircle(c.onEnvelope.copy(alpha = a), radius = r)
        } else {
            // Yellow alone disappears on paper: ring it in ink, blink the fill.
            drawCircle(c.envelope.copy(alpha = a), radius = r)
            drawCircle(c.ink, radius = r - 0.6.dp.toPx(), style = Stroke(1.2.dp.toPx()))
        }
    }
}

// ── Tags ───────────────────────────────────────────────────────────────────

enum class TagKind { Saved, Job, Waiting, Reject, Quiet }

/** Mono caps status tag in a hairline box: SAVED, SENDING, WAITING, FAILED, ON COMPUTER. */
@Composable
fun StatusTag(text: String, kind: TagKind, modifier: Modifier = Modifier) {
    val c = PherryTheme.colors
    val (fg, bg) = when (kind) {
        TagKind.Saved -> c.ink to Color.Transparent
        TagKind.Job -> c.onEnvelope to c.envelope
        TagKind.Waiting -> c.ink2 to Color.Transparent
        TagKind.Reject -> c.red to Color.Transparent
        TagKind.Quiet -> c.ink3 to Color.Transparent
    }
    Box(
        modifier = modifier
            .clip(PherryShape.frame)
            .background(bg)
            .border(1.dp, if (kind == TagKind.Job) bg else fg, PherryShape.frame)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(text.uppercase(), style = PherryTheme.text.edge, color = fg, maxLines = 1)
    }
}

// ── Notices ────────────────────────────────────────────────────────────────

/** A problem the user can act on: red wash, a plain sentence, the fix as a button. */
@Composable
fun Notice(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    error: Boolean = true,
) {
    val c = PherryTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PherryShape.print)
            .background(if (error) c.redWash else c.well)
            .padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm)
            .heightIn(min = Spacing.touch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhIcon(if (error) Ph.WarningCircle else Ph.Info, contentDescription = null, tint = if (error) c.red else c.ink2, size = 20.dp)
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f).padding(vertical = Spacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (error) c.onRedWash else c.ink)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = if (error) c.onRedWash.copy(alpha = 0.8f) else c.ink2)
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                colors = ButtonDefaults.textButtonColors(contentColor = if (error) c.red else c.ink),
                shape = PherryShape.button,
                modifier = Modifier.heightIn(min = Spacing.touch),
            ) { Text(actionLabel, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

// ── Empty ──────────────────────────────────────────────────────────────────

/** An unexposed strip of film: four dashed frames, a plain sentence, maybe an action. */
@Composable
fun EmptyStrip(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val c = PherryTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.xxl, horizontal = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) {
                Canvas(Modifier.width(48.dp).aspectRatio(4f / 3f)) {
                    drawRoundRect(
                        color = c.ruleStrong,
                        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.lg))
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.xs))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.ink2, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(Spacing.lg))
            action()
        }
    }
}

// ── Headers ────────────────────────────────────────────────────────────────

/** Screen title with its data line *below* it (counts, dates) in mono caps. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    data: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = PherryTheme.colors
    Row(modifier.fillMaxWidth().padding(top = Spacing.md, bottom = Spacing.lg), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = c.ink, modifier = Modifier.semantics { heading() })
            if (data != null) {
                Spacer(Modifier.height(6.dp))
                Text(data.uppercase(), style = PherryTheme.text.monoCaps, color = c.ink3)
            }
        }
        trailing?.invoke()
    }
}

/** A section heading inside a screen: condensed printed caps over a hairline. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val c = PherryTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(top = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text.uppercase(),
                style = PherryTheme.text.formLabel,
                color = c.ink2,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            trailing?.invoke()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.rule))
    }
}

/** Sticky "guide words" header for long lists: names the span in view, e.g. "OCT 1 — SEP 28". */
@Composable
fun GuideWords(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    val c = PherryTheme.colors
    Column(modifier.fillMaxWidth().background(c.paper)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text.uppercase(), style = PherryTheme.text.monoCaps, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
            if (trailing != null) Text(trailing.uppercase(), style = PherryTheme.text.monoCaps, color = c.ink3)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.rule))
    }
}

// ── Settings rows ──────────────────────────────────────────────────────────

/** A whole-row switch: title, plain-language consequence, ink track with a yellow knob. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    val c = PherryTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            PhIcon(icon, contentDescription = null, tint = c.ink2, size = 22.dp)
            Spacer(Modifier.width(Spacing.lg))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.ink2)
        }
        Spacer(Modifier.width(Spacing.md))
        // On paper: a yellow knob on an ink track. In the darkroom the ink is pale, so the track turns
        // yellow and the knob ink, as on the desktop.
        val onTrack = if (c.isDark) c.envelope else c.ink
        val onThumb = if (c.isDark) c.onEnvelope else c.envelope
        val onIcon = if (c.isDark) c.envelope else c.onEnvelope
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = onThumb,
                checkedTrackColor = onTrack,
                checkedBorderColor = onTrack,
                checkedIconColor = onIcon,
                uncheckedThumbColor = c.ink3,
                uncheckedTrackColor = c.well2,
                uncheckedBorderColor = c.ruleStrong,
                // Disabled keeps its on/off colours; the row's alpha already does the fading.
                disabledCheckedThumbColor = onThumb,
                disabledCheckedTrackColor = onTrack,
                disabledCheckedBorderColor = onTrack,
                disabledCheckedIconColor = onIcon,
                disabledUncheckedThumbColor = c.ink3,
                disabledUncheckedTrackColor = c.well2,
                disabledUncheckedBorderColor = c.ruleStrong,
            ),
        )
    }
}

/** A tappable settings row with a trailing value or chevron. */
@Composable
fun ActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    value: String? = null,
    danger: Boolean = false,
) {
    val c = PherryTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            PhIcon(icon, contentDescription = null, tint = if (danger) c.red else c.ink2, size = 22.dp)
            Spacer(Modifier.width(Spacing.lg))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (danger) c.red else c.ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.ink2)
        }
        if (value != null) {
            Text(value, style = PherryTheme.text.mono, color = c.ink2, maxLines = 1)
            Spacer(Modifier.width(Spacing.sm))
        }
        if (!danger) PhIcon(Ph.CaretRight, contentDescription = null, tint = c.ink3, size = 18.dp)
    }
}

// ── Segmented ──────────────────────────────────────────────────────────────

/** Printed tabs: ink outline, the chosen option printed solid. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> PrintSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PherryTheme.colors
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size, baseShape = PherryShape.button),
                icon = {},
                border = SegmentedButtonDefaults.borderStroke(c.ink, 1.5.dp),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = c.ink,
                    activeContentColor = c.paper,
                    activeBorderColor = c.ink,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = c.ink,
                    inactiveBorderColor = c.ink,
                ),
                label = { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) },
            )
        }
    }
}

// ── Hairline ───────────────────────────────────────────────────────────────

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = PherryTheme.colors.rule) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}
