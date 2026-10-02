package com.appharbor.pherry.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.appharbor.pherry.ui.theme.PherryTheme
import com.appharbor.pherry.ui.theme.Spacing

/** A print corner rectangle with the envelope's thumb-cut: a half-circle bitten from the top edge. */
class ThumbCutShape(
    private val notchRadius: Dp = 14.dp,
    private val corner: Dp = 4.dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { notchRadius.toPx() }
        val c = with(density) { corner.toPx() }
        val body = Path().apply {
            addRoundRect(RoundRect(0f, 0f, size.width, size.height, c, c))
        }
        val notch = Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(Offset(size.width / 2f, 0f), r))
        }
        return Outline.Generic(Path.combine(PathOperation.Difference, body, notch))
    }
}

/**
 * The job envelope: a committed field of envelope yellow. Holds the one thing a screen is about
 * (what is waiting, what is sending). Content inherits on-envelope ink.
 */
@Composable
fun Envelope(
    modifier: Modifier = Modifier,
    notch: Boolean = true,
    contentPadding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = PherryTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(if (notch) ThumbCutShape() else com.appharbor.pherry.ui.theme.PherryShape.print)
            .background(colors.envelope)
            .padding(start = contentPadding, end = contentPadding, top = contentPadding + 6.dp, bottom = contentPadding),
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.onEnvelope) {
            content()
        }
    }
}

/**
 * Printed label + value pair on the envelope ("ON PHONE / 4,212"). A [lamp] prints before the value
 * ("TO / ● ALEX-PC"); a value too long for the line ends in an ellipsis after it.
 */
@Composable
fun EnvelopeField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueStyle: androidx.compose.ui.text.TextStyle = PherryTheme.text.envelopeValue,
    lamp: LampState? = null,
) {
    val colors = PherryTheme.colors
    Column(modifier) {
        Text(label.uppercase(), style = PherryTheme.text.formLabel, color = colors.onEnvelope2, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (lamp != null) {
                Lamp(lamp, onEnvelope = true)
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(value, style = valueStyle, color = colors.onEnvelope, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * An order-form checkbox printed on the envelope: square ink box, the whole row toggles.
 * Disabled rows print faded and say why through [disabledReason] (for TalkBack).
 */
@Composable
fun EnvelopeCheck(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledReason: String? = null,
) {
    val colors = PherryTheme.colors
    Row(
        modifier = modifier
            .heightIn(min = Spacing.touch)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .then(
                if (!enabled && disabledReason != null) {
                    // Replaces "checked/not checked" so TalkBack says why the box can't be ticked.
                    Modifier.semantics {
                        stateDescription = "${if (checked) "Checked" else "Not checked"}. $disabledReason"
                    }
                } else {
                    Modifier
                }
            )
            .alpha(if (enabled) 1f else 0.45f)
            .padding(end = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(com.appharbor.pherry.ui.theme.PherryShape.frame)
                .then(
                    if (checked) Modifier.background(colors.onEnvelope)
                    else Modifier.border(1.75.dp, colors.onEnvelope, com.appharbor.pherry.ui.theme.PherryShape.frame)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) PhIcon(Ph.CheckBold, contentDescription = null, tint = colors.envelope, size = 14.dp)
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelLarge, color = colors.onEnvelope)
    }
}

/** Rubber date stamp: double-ruled box, mono caps, set slightly off true like a real stamp. */
@Composable
fun DateStamp(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = PherryTheme.colors.onEnvelope,
) {
    Box(
        modifier = modifier
            .rotate(-1.5f)
            .border(1.5.dp, color.copy(alpha = 0.85f), com.appharbor.pherry.ui.theme.PherryShape.frame)
            .padding(2.dp)
            .border(0.75.dp, color.copy(alpha = 0.6f), com.appharbor.pherry.ui.theme.PherryShape.frame)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text.uppercase(), style = PherryTheme.text.monoCaps, color = color)
    }
}

/** A row of punched holes: separates a ticket's stub, or a section of the envelope. */
@Composable
fun Perforation(
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = PherryTheme.colors.ruleStrong,
) {
    androidx.compose.foundation.Canvas(modifier = modifier.fillMaxWidth().height(4.dp)) {
        val step = 9.dp.toPx()
        val r = 1.5.dp.toPx()
        var x = step / 2
        while (x < size.width) {
            drawCircle(color, radius = r, center = Offset(x, size.height / 2))
            x += step
        }
    }
}

/** Ink progress bar printed on the envelope. Square, thick, quietly marked every 10%. */
@Composable
fun JobBar(
    progress: Float,
    modifier: Modifier = Modifier,
    onEnvelope: Boolean = true,
) {
    val colors = PherryTheme.colors
    val ink = if (onEnvelope) colors.onEnvelope else colors.ink
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f) },
    ) {
        val p = progress.coerceIn(0f, 1f)
        drawRect(ink.copy(alpha = 0.16f))
        drawRect(ink, size = Size(size.width * p, size.height))
        val tick = 1.dp.toPx()
        for (i in 1..9) {
            val x = size.width * i / 10f
            drawRect(
                color = if (x < size.width * p) (if (onEnvelope) colors.envelope else colors.paper).copy(alpha = 0.5f) else ink.copy(alpha = 0.22f),
                topLeft = Offset(x - tick / 2, 0f),
                size = Size(tick, size.height),
            )
        }
    }
}

@Composable
internal fun EnvelopeSpacer(height: Dp = Spacing.lg) = Spacer(Modifier.height(height))

@Composable
fun EnvelopeRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) = Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg), content = content)
