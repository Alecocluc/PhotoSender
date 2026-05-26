package com.appharbor.pherry.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.appharbor.pherry.data.db.UploadStatus

@Composable
fun TransferProgressRow(
    title: String,
    subtitle: String,
    progress: Float,
    status: UploadStatus,
    modifier: Modifier = Modifier,
    leadingBoxColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    leadingContent: @Composable BoxScope.() -> Unit,
    progressBrush: Brush? = null,
) {
    val clamped = progress.coerceIn(0f, 1f)
    MediaListRow(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        leadingBoxColor = leadingBoxColor,
        leadingContent = leadingContent,
        trailingContent = { StatusBadge(status = status) },
        belowContent = {
            GradientProgressBar(
                progress = clamped,
                brush = progressBrush,
                animated = status == UploadStatus.UPLOADING,
            )
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "${(clamped * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
