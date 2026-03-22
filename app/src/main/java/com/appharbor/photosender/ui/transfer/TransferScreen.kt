package com.appharbor.photosender.ui.transfer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.appharbor.photosender.data.db.UploadRecord
import com.appharbor.photosender.data.db.UploadStatus
import com.appharbor.photosender.ui.theme.LocalExtendedColors
import com.appharbor.photosender.data.upload.FileTransferProgress

@Composable
fun TransferScreen(
    viewModel: TransferViewModel = hiltViewModel()
) {
    val state by viewModel.transferState.collectAsStateWithLifecycle()
    val recentBatch by viewModel.recentBatch.collectAsStateWithLifecycle()

    val animatedProgress by animateFloatAsState(
        targetValue = state.progressPercent,
        animationSpec = tween(300),
        label = "progress",
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        // Header
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "GLOBAL STATUS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = "${(state.progressPercent * 100).toInt()}% Sent",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = viewModel.formatSpeed(state.currentSpeedBytesPerSec),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val eta = viewModel.formatTime(state.estimatedSecondsRemaining)
                    if (eta.isNotEmpty()) {
                        Text(
                            text = "Est. $eta",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Global progress bar — gradient from primary to secondary per design system
            val extendedColors = LocalExtendedColors.current
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .drawBehind {
                        val indicatorWidth = size.width * animatedProgress
                        drawRoundRect(
                            brush = extendedColors.progressGradient,
                            size = Size(indicatorWidth, size.height),
                            cornerRadius = CornerRadius(3.dp.toPx()),
                        )
                    }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Active Queue header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Active Queue",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (state.isTransferring) {
                    OutlinedButton(
                        onClick = viewModel::cancelTransfer,
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Icon(
                            Icons.Filled.Cancel,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Cancel",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Transfer items
        if (state.activeTransfers.isEmpty() && !state.isTransferring) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No active transfers",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Select files from the Gallery to start transferring",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        } else {
            items(state.activeTransfers, key = { it.recordId }) { transfer ->
                TransferItem(transfer = transfer, formatBytes = viewModel::formatBytes)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Stats summary
        if (state.totalFiles > 0) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.CloudUpload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "${state.completedFiles}/${state.totalFiles} files transferred",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (state.failedFiles > 0) {
                            Text(
                                "${state.failedFiles} failed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Text(
                            "${viewModel.formatBytes(state.transferredBytes)} / ${viewModel.formatBytes(state.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }

        // Recent Batch section
        if (recentBatch.isNotEmpty()) {
            item {
                Text(
                    text = "Recent Batch",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(recentBatch, key = { it.id }) { record ->
                        RecentBatchThumbnail(record = record)
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun TransferItem(
    transfer: FileTransferProgress,
    formatBytes: (Long) -> String,
) {
    val progress = if (transfer.fileSize > 0) {
        transfer.bytesTransferred.toFloat() / transfer.fileSize
    } else 0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // File type icon
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (transfer.contentUri.isNotEmpty() && canPreviewThumbnail(transfer.fileName)) {
                AsyncImage(
                    model = transfer.contentUri,
                    contentDescription = transfer.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = when (transfer.status) {
                        UploadStatus.UPLOADING -> Icons.Filled.CloudUpload
                        UploadStatus.COMPLETED -> Icons.Filled.CheckCircle
                        else -> Icons.Filled.HourglassEmpty
                    },
                    contentDescription = null,
                    tint = when (transfer.status) {
                        UploadStatus.UPLOADING -> MaterialTheme.colorScheme.primary
                        UploadStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = transfer.fileName.take(28) + if (transfer.fileName.length > 28) "..." else "",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                StatusChip(status = transfer.status)
            }
            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "${formatBytes(transfer.bytesTransferred)} / ${formatBytes(transfer.fileSize)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Progress bar — gradient for active uploads, flat for completed/pending
            val extColors = LocalExtendedColors.current
            val completedColor = MaterialTheme.colorScheme.tertiary
            val pendingColor = MaterialTheme.colorScheme.outlineVariant
            val progressBrush = when (transfer.status) {
                UploadStatus.UPLOADING -> extColors.progressGradient
                UploadStatus.COMPLETED -> Brush.horizontalGradient(listOf(completedColor, completedColor))
                else -> Brush.horizontalGradient(listOf(pendingColor, pendingColor))
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .drawBehind {
                        val indicatorWidth = size.width * progress
                        drawRoundRect(
                            brush = progressBrush,
                            size = Size(indicatorWidth, size.height),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )
                    }
            )

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatBytes(transfer.fileSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun RecentBatchThumbnail(record: UploadRecord) {
    Box(
        modifier = Modifier
            .size(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (record.contentUri.isNotEmpty() && canPreviewThumbnail(record.fileName)) {
            AsyncImage(
                model = record.contentUri,
                contentDescription = record.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Filled.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(32.dp)
                    .align(Alignment.Center),
            )
        }
        // Check badge
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Completed",
                tint = MaterialTheme.colorScheme.onTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun canPreviewThumbnail(fileName: String): Boolean {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
}

@Composable
private fun StatusChip(status: UploadStatus) {
    val (label, container, content) = when (status) {
        UploadStatus.UPLOADING -> Triple(
            "UPLOADING",
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f),
            MaterialTheme.colorScheme.tertiary,
        )
        UploadStatus.COMPLETED -> Triple(
            "DONE",
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f),
            MaterialTheme.colorScheme.primary,
        )
        UploadStatus.PENDING -> Triple(
            "PENDING",
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        UploadStatus.FAILED -> Triple(
            "FAILED",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.error,
        )
    }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = container,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
