package com.appharbor.photosender.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appharbor.photosender.data.db.UploadStatus

enum class BadgeVariant { UPLOADING, DONE, PENDING, FAILED, SKIPPED }

@Composable
fun StatusBadge(
    variant: BadgeVariant,
    modifier: Modifier = Modifier,
) {
    val (label, container, content) = when (variant) {
        BadgeVariant.UPLOADING -> Triple(
            "UPLOADING",
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f),
            MaterialTheme.colorScheme.tertiary,
        )
        BadgeVariant.DONE -> Triple(
            "DONE",
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f),
            MaterialTheme.colorScheme.primary,
        )
        BadgeVariant.PENDING -> Triple(
            "PENDING",
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BadgeVariant.FAILED -> Triple(
            "FAILED",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.error,
        )
        BadgeVariant.SKIPPED -> Triple(
            "SKIPPED",
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
            MaterialTheme.colorScheme.secondary,
        )
    }
    StatusBadgeRaw(label = label, containerColor = container, contentColor = content, modifier = modifier)
}

@Composable
fun StatusBadge(
    status: UploadStatus,
    modifier: Modifier = Modifier,
) = StatusBadge(status.toBadgeVariant(), modifier)

fun UploadStatus.toBadgeVariant(): BadgeVariant = when (this) {
    UploadStatus.UPLOADING -> BadgeVariant.UPLOADING
    UploadStatus.COMPLETED -> BadgeVariant.DONE
    UploadStatus.PENDING -> BadgeVariant.PENDING
    UploadStatus.FAILED -> BadgeVariant.FAILED
}

@Composable
fun StatusBadgeRaw(
    label: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
