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
        val chevronStroke = Stroke(
            width = s * 0.085f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val chevron = Path().apply {
            moveTo(s * 0.40f, s * 0.27f)
            lineTo(s * 0.60f, s * 0.47f)
            lineTo(s * 0.40f, s * 0.67f)
        }
        drawPath(chevron, color = glyphColor, style = chevronStroke)

        val wave = Path().apply {
            moveTo(s * 0.24f, s * 0.72f)
            cubicTo(s * 0.36f, s * 0.62f, s * 0.47f, s * 0.80f, s * 0.59f, s * 0.70f)
            cubicTo(s * 0.67f, s * 0.64f, s * 0.74f, s * 0.66f, s * 0.81f, s * 0.70f)
        }
        drawPath(
            path = wave,
            color = glyphColor.copy(alpha = 0.9f),
            style = Stroke(width = s * 0.055f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
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
