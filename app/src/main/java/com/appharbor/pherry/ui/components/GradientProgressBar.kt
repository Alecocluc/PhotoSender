package com.appharbor.pherry.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.appharbor.pherry.ui.theme.LocalExtendedColors

@Composable
fun GradientProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    brush: Brush? = null,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    animated: Boolean = false,
) {
    val resolvedBrush = brush ?: LocalExtendedColors.current.progressGradient
    val radius = height / 2
    val clamped = progress.coerceIn(0f, 1f)

    val phase = if (animated) {
        val transition = rememberInfiniteTransition(label = "flow")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "flowPhase",
        ).value
    } else 0f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(trackColor)
            .drawBehind {
                val w = size.width * clamped
                if (w <= 0f) return@drawBehind
                val cr = CornerRadius(radius.toPx())
                drawRoundRect(brush = resolvedBrush, size = Size(w, size.height), cornerRadius = cr)
                if (animated) {
                    // a soft light band gliding left→right over the filled region (ferry flow)
                    val bandW = size.width * 0.28f
                    val start = -bandW + (w + bandW) * phase
                    val highlight = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.34f),
                            Color.Transparent,
                        ),
                        start = Offset(start, 0f),
                        end = Offset(start + bandW, 0f),
                    )
                    drawRoundRect(brush = highlight, size = Size(w, size.height), cornerRadius = cr)
                }
            },
    )
}
