package com.appharbor.pherry.ui.components

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
) {
    val resolvedBrush = brush ?: LocalExtendedColors.current.progressGradient
    val radius = height / 2
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(trackColor)
            .drawBehind {
                val w = size.width * progress.coerceIn(0f, 1f)
                drawRoundRect(
                    brush = resolvedBrush,
                    size = Size(w, size.height),
                    cornerRadius = CornerRadius(radius.toPx()),
                )
            }
    )
}
