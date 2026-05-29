package com.appharbor.pherry.ui.components

import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.appharbor.pherry.R
import com.appharbor.pherry.ui.theme.Manrope

/**
 * The Pherry app mark — the same artwork as the launcher icon
 * (see drawable/ic_pherry_logo.xml). Rendered from the shared asset so the
 * in-app brand always matches the home-screen icon.
 */
@Composable
fun PherryMark(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    cornerRadius: Dp = 9.dp,
) {
    Image(
        painter = painterResource(R.drawable.ic_pherry_logo),
        contentDescription = null,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius)),
    )
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
