package com.appharbor.pherry.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.appharbor.pherry.ui.theme.LocalExtendedColors
import com.appharbor.pherry.ui.theme.Manrope

/**
 * The Pherry mark: a teal tile with a white forward double-chevron ("ferry crossing / send").
 * Simplified vs. the launcher icon (drops the wave) so it stays crisp at small sizes.
 */
@Composable
fun PherryMark(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    cornerRadius: Dp = 9.dp,
    glyphColor: Color = Color.White,
) {
    val brush = LocalExtendedColors.current.buttonGradient
    Canvas(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius)),
    ) {
        drawRect(brush = brush)
        val s = this.size.minDimension
        val stroke = Stroke(width = s * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun chevron(x0: Float) = Path().apply {
            moveTo(s * x0, s * 0.31f)
            lineTo(s * (x0 + 0.18f), s * 0.50f)
            lineTo(s * x0, s * 0.69f)
        }
        drawPath(chevron(0.36f), color = glyphColor, style = stroke)
        drawPath(chevron(0.54f), color = glyphColor.copy(alpha = 0.5f), style = stroke)
    }
}

@Composable
fun PherryWordmark(
    modifier: Modifier = Modifier,
    markSize: Dp = 26.dp,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        PherryMark(size = markSize, cornerRadius = (markSize.value * 0.32f).dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Pherry",
            style = MaterialTheme.typography.titleLarge,
            fontFamily = Manrope,
            fontWeight = FontWeight.ExtraBold,
            color = color,
        )
    }
}
