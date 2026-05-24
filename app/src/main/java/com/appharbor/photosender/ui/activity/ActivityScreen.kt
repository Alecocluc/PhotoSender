package com.appharbor.photosender.ui.activity

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.appharbor.photosender.data.db.UploadRecord
import com.appharbor.photosender.data.db.UploadStatus
import com.appharbor.photosender.data.upload.DedupSkip
import com.appharbor.photosender.data.upload.FileTransferProgress
import com.appharbor.photosender.ui.components.BadgeVariant
import com.appharbor.photosender.ui.components.EmptyState
import com.appharbor.photosender.ui.components.GradientProgressBar
import com.appharbor.photosender.ui.components.MediaListRow
import com.appharbor.photosender.ui.components.ScreenHeader
import com.appharbor.photosender.ui.components.SegmentedToggle
import com.appharbor.photosender.ui.components.StatCard
import com.appharbor.photosender.ui.components.StatusBadge
import com.appharbor.photosender.ui.history.HistoryViewModel
import com.appharbor.photosender.ui.settings.SettingsViewModel
import com.appharbor.photosender.ui.theme.LocalExtendedColors
import com.appharbor.photosender.ui.theme.Spacing
import com.appharbor.photosender.ui.transfer.TransferViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class ActivityTab { LIVE, HISTORY }

@Composable
fun ActivityScreen(
    transferViewModel: TransferViewModel = hiltViewModel(),
    historyViewModel: HistoryViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    var selectedTab by rememberSaveable { mutableStateOf(ActivityTab.LIVE) }
    val transferState by transferViewModel.transferState.collectAsStateWithLifecycle()
    val keepScreenAwake by settingsViewModel.keepScreenAwake.collectAsStateWithLifecycle()
    val view = LocalView.current

    DisposableEffect(keepScreenAwake, transferState.isTransferring) {
        view.keepScreenOn = keepScreenAwake && transferState.isTransferring
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screen),
    ) {
        Spacer(Modifier.height(Spacing.md))
        ScreenHeader(title = "Activity", subtitle = "Transfers & history")
        Spacer(Modifier.height(Spacing.md))

        SegmentedToggle(
            options = listOf(ActivityTab.LIVE to "Live", ActivityTab.HISTORY to "History"),
            selected = selectedTab,
            onSelect = { selectedTab = it },
            fillWidth = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Spacing.lg))

        when (selectedTab) {
            ActivityTab.LIVE -> LiveSegment(transferViewModel)
            ActivityTab.HISTORY -> HistorySegment(historyViewModel)
        }
    }
}

// ── Live segment ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveSegment(viewModel: TransferViewModel) {
    val state by viewModel.transferState.collectAsStateWithLifecycle()
    val animatedProgress by animateFloatAsState(
        targetValue = state.progressPercent,
        animationSpec = tween(300),
        label = "global_progress",
    )

    LazyColumn(modifier = Modifier.fillMaxSize()) {

        // Global progress header — pinned at the top
        if (state.totalFiles > 0) {
            stickyHeader {
                Column(
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                ) {
                    LiveProgressHeader(
                        progressPercent = animatedProgress,
                        completedFiles = state.completedFiles,
                        totalFiles = state.totalFiles,
                        speedBytesPerSec = state.currentSpeedBytesPerSec,
                        etaSeconds = state.estimatedSecondsRemaining,
                        transferredBytes = state.transferredBytes,
                        totalBytes = state.totalBytes,
                        failedFiles = state.failedFiles,
                        skippedFiles = state.skippedFiles,
                        isTransferring = state.isTransferring,
                        onCancel = viewModel::cancelTransfer,
                        formatBytes = viewModel::formatBytes,
                        formatSpeed = viewModel::formatSpeed,
                        formatTime = viewModel::formatTime,
                    )
                    Spacer(Modifier.height(Spacing.lg))
                }
            }
        }

        // Transfer items — partitioned by status so 22k-file batches stay fast
        val uploading = state.activeTransfers.filter { it.status == UploadStatus.UPLOADING }
        val pendingCount = state.activeTransfers.count { it.status == UploadStatus.PENDING }
        val completedCount = state.activeTransfers.count { it.status == UploadStatus.COMPLETED }
        val failed = state.activeTransfers.filter { it.status == UploadStatus.FAILED }
        val hasAny = state.activeTransfers.isNotEmpty()

        if (!hasAny && !state.isTransferring) {
            item {
                EmptyState(
                    icon = Icons.Filled.CloudUpload,
                    title = "No active transfers",
                    subtitle = "Select files from Gallery to start",
                    modifier = Modifier.padding(vertical = 48.dp),
                )
            }
        } else {
            // Section header + cancel
            if (hasAny) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Active Queue",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (state.isTransferring) {
                            OutlinedButton(
                                onClick = viewModel::cancelTransfer,
                                shape = MaterialTheme.shapes.extraLarge,
                            ) {
                                Icon(
                                    Icons.Filled.Cancel,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "Cancel",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }
            }

            // UPLOADING files — show individually (usually 1–3 concurrent)
            items(uploading, key = { it.recordId }) { transfer ->
                TransferRow(transfer = transfer, formatBytes = viewModel::formatBytes)
                Spacer(Modifier.height(Spacing.sm))
            }

            // PENDING summary — single row instead of N cards
            if (pendingCount > 0) {
                item {
                    TransferSummaryRow(
                        icon = Icons.Filled.HourglassEmpty,
                        label = "$pendingCount file${if (pendingCount == 1) "" else "s"} waiting…",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                }
            }

            // COMPLETED summary — single row
            if (completedCount > 0) {
                item {
                    TransferSummaryRow(
                        icon = Icons.Filled.CheckCircle,
                        label = "$completedCount file${if (completedCount == 1) "" else "s"} done",
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                }
            }

            // FAILED files — always show individually
            items(failed, key = { "failed_${it.recordId}" }) { transfer ->
                TransferRow(transfer = transfer, formatBytes = viewModel::formatBytes)
                Spacer(Modifier.height(Spacing.sm))
            }
        }

        // Skipped duplicates — show first 3, then summary
        if (state.skippedDuplicates.isNotEmpty()) {
            item {
                Spacer(Modifier.height(Spacing.md))
                Text(
                    "Skipped Duplicates",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(Spacing.sm))
            }
            val shown = state.skippedDuplicates.take(3)
            val hiddenCount = state.skippedDuplicates.size - shown.size
            items(shown, key = { it.skippedRecordId }) { skip ->
                DuplicateRow(skip = skip, formatBytes = viewModel::formatBytes)
                Spacer(Modifier.height(Spacing.sm))
            }
            if (hiddenCount > 0) {
                item {
                    TransferSummaryRow(
                        icon = Icons.Filled.ContentCopy,
                        label = "…and $hiddenCount more duplicate${if (hiddenCount == 1) "" else "s"} skipped",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                }
            }
        }

        item { Spacer(Modifier.height(Spacing.xxl)) }
    }
}

@Composable
private fun LiveProgressHeader(
    progressPercent: Float,
    completedFiles: Int,
    totalFiles: Int,
    speedBytesPerSec: Long,
    etaSeconds: Long,
    transferredBytes: Long,
    totalBytes: Long,
    failedFiles: Int,
    skippedFiles: Int,
    isTransferring: Boolean,
    onCancel: () -> Unit,
    formatBytes: (Long) -> String,
    formatSpeed: (Long) -> String,
    formatTime: (Long) -> String,
) {
    val extColors = LocalExtendedColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(Spacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "${(progressPercent * 100).toInt()}%",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Column(horizontalAlignment = Alignment.End) {
                if (isTransferring && speedBytesPerSec > 0) {
                    Text(
                        text = formatSpeed(speedBytesPerSec),
                        style = MaterialTheme.typography.titleMedium.copy(brush = extColors.progressGradient),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                val eta = formatTime(etaSeconds)
                if (eta.isNotEmpty()) {
                    Text(
                        text = "Est. $eta",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.sm))
        GradientProgressBar(progress = progressPercent, height = 6.dp)
        Spacer(Modifier.height(Spacing.sm))

        Text(
            text = buildString {
                append("$completedFiles / $totalFiles files")
                append(" · ${formatBytes(transferredBytes)} / ${formatBytes(totalBytes)}")
                if (failedFiles > 0) append(" · $failedFiles failed")
                if (skippedFiles > 0) append(" · $skippedFiles skipped")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (isTransferring) {
            Spacer(Modifier.height(Spacing.md))
            OutlinedButton(
                onClick = onCancel,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(
                    Icons.Filled.Cancel,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    "Cancel transfer",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun TransferRow(
    transfer: FileTransferProgress,
    formatBytes: (Long) -> String,
) {
    val progress = if (transfer.fileSize > 0) {
        transfer.bytesTransferred.toFloat() / transfer.fileSize
    } else 0f

    val leadingColor = when (transfer.status) {
        UploadStatus.COMPLETED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        UploadStatus.UPLOADING -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }

    MediaListRow(
        title = transfer.fileName,
        subtitle = "${formatBytes(transfer.bytesTransferred)} / ${formatBytes(transfer.fileSize)}",
        leadingBoxColor = leadingColor,
        leadingContent = {
            if (transfer.contentUri.isNotEmpty() && canPreviewThumbnail(transfer.fileName)) {
                AsyncImage(
                    model = transfer.contentUri,
                    contentDescription = null,
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
        },
        trailingContent = { StatusBadge(status = transfer.status) },
        belowContent = {
            val extColors = LocalExtendedColors.current
            val progressBrush = when (transfer.status) {
                UploadStatus.UPLOADING -> extColors.progressGradient
                UploadStatus.COMPLETED -> androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiary)
                )
                else -> androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.outlineVariant)
                )
            }
            GradientProgressBar(progress = progress, brush = progressBrush)
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun DuplicateRow(
    skip: DedupSkip,
    formatBytes: (Long) -> String,
) {
    val matchedName = skip.matchedFileName ?: "existing desktop file"
    val matchedBucket = skip.matchedBucketName ?: if (skip.matchedOnPhone) "Phone history" else "Desktop"

    MediaListRow(
        title = skip.skippedFileName,
        subtitle = "Same as $matchedName",
        subtitle2 = "${skip.skippedBucketName} → $matchedBucket · ${formatBytes(skip.fileSize)}",
        leadingBoxColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
        leadingContent = {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(22.dp),
            )
        },
        trailingContent = { StatusBadge(variant = BadgeVariant.SKIPPED) },
    )
}

// ── History segment ──────────────────────────────────────────────────────────

private data class HistoryGroup(val label: String, val items: List<com.appharbor.photosender.data.db.UploadRecord>)

private fun groupHistoryByDate(items: List<com.appharbor.photosender.data.db.UploadRecord>): List<HistoryGroup> {
    if (items.isEmpty()) return emptyList()
    val now = Calendar.getInstance()
    val todayYear = now.get(Calendar.YEAR); val todayDay = now.get(Calendar.DAY_OF_YEAR)
    val yesterday = Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, -1) }
    val yYear = yesterday.get(Calendar.YEAR); val yDay = yesterday.get(Calendar.DAY_OF_YEAR)
    fun label(ts: Long): String {
        if (ts <= 0) return "Older"
        val c = Calendar.getInstance().also { it.timeInMillis = ts }
        return when {
            c.get(Calendar.YEAR) == todayYear && c.get(Calendar.DAY_OF_YEAR) == todayDay -> "Today"
            c.get(Calendar.YEAR) == yYear && c.get(Calendar.DAY_OF_YEAR) == yDay -> "Yesterday"
            else -> SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(ts))
        }
    }
    val groups = LinkedHashMap<String, MutableList<com.appharbor.photosender.data.db.UploadRecord>>()
    for (item in items) groups.getOrPut(label(item.uploadedAt)) { mutableListOf() }.add(item)
    return groups.map { (l, its) -> HistoryGroup(l, its) }
}

private fun formatHistoryTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val diff = System.currentTimeMillis() - timestamp
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    return when {
        diff < 60_000L -> "Just now"
        diff < 3_600_000L -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        diff < 172_800_000L -> "Yesterday at $time"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}

@Composable
private fun HistorySegment(viewModel: HistoryViewModel) {
    val completedCount by viewModel.completedCount.collectAsStateWithLifecycle()
    val failedCount by viewModel.failedCount.collectAsStateWithLifecycle()
    val verifyState by viewModel.verifyState.collectAsStateWithLifecycle()
    val totalTransferredBytes by viewModel.totalTransferredBytes.collectAsStateWithLifecycle()
    val lastSync by viewModel.lastSyncTimestamp.collectAsStateWithLifecycle()
    val recentHistory by viewModel.recentHistory.collectAsStateWithLifecycle()
    val groups = androidx.compose.runtime.remember(recentHistory) { groupHistoryByDate(recentHistory) }

    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear History") },
            text = { Text("This will remove all transfer records. This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    showClearDialog = false
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            },
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {

        // Stat cards row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                StatCard(
                    icon = Icons.Filled.Cloud,
                    label = "Transferred",
                    value = viewModel.formatBytes(totalTransferredBytes),
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    icon = Icons.Filled.Verified,
                    label = "Successful",
                    value = "$completedCount",
                    modifier = Modifier.weight(1f),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
            Spacer(Modifier.height(Spacing.md))
        }

        // Last sync card
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(Spacing.md))
                Column {
                    Text(
                        text = "Last Sync",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (lastSync > 0) {
                            SimpleDateFormat("MMM dd, yyyy · HH:mm", Locale.getDefault()).format(Date(lastSync))
                        } else {
                            "No sync yet"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
        }

        // Backup integrity
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(Spacing.lg),
            ) {
                Text(
                    text = "Backup integrity",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val statusLine = when {
                    verifyState.isVerifying -> "Verifying ${verifyState.checked}/${verifyState.total}…"
                    verifyState.summary != null -> verifyState.summary!!
                    failedCount > 0 -> "$failedCount file(s) failed to send."
                    else -> "Check every sent file is present on the desktop."
                }
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failedCount > 0 && !verifyState.isVerifying && verifyState.summary == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(Spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    OutlinedButton(
                        onClick = { viewModel.verifyBackup() },
                        enabled = !verifyState.isVerifying && completedCount > 0,
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Icon(Icons.Filled.Verified, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(Spacing.sm - 2.dp))
                        Text("Verify backup", style = MaterialTheme.typography.labelMedium)
                    }
                    if (failedCount > 0) {
                        OutlinedButton(
                            onClick = { viewModel.retryFailed() },
                            shape = MaterialTheme.shapes.extraLarge,
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(Spacing.sm - 2.dp))
                            Text(
                                "Retry $failedCount",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.lg))
        }

        // Clear button (compact, right-aligned)
        item {
            if (recentHistory.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showClearDialog = true }) {
                        Text(
                            "Clear all",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }

        if (groups.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Description,
                    title = "No transfers yet",
                    subtitle = "Your completed transfers will appear here",
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            }
        } else {
            groups.forEach { group ->
                item(key = "header_${group.label}") {
                    Text(
                        text = group.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs),
                    )
                }
                items(group.items, key = { "record_${it.id}" }) { record ->
                    HistoryRow(record = record, formatBytes = viewModel::formatBytes)
                    Spacer(Modifier.height(Spacing.sm))
                }
            }
        }

        item { Spacer(Modifier.height(Spacing.xxl)) }
    }
}

@Composable
private fun HistoryRow(
    record: UploadRecord,
    formatBytes: (Long) -> String,
) {
    MediaListRow(
        title = record.fileName,
        subtitle = "${formatBytes(record.fileSize)} · ${record.bucketName}",
        leadingBoxColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
        leadingContent = {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(22.dp),
            )
        },
        trailingContent = {
            val timeLabel = formatHistoryTime(record.uploadedAt)
            if (timeLabel.isNotEmpty()) {
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun TransferSummaryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

private fun canPreviewThumbnail(fileName: String): Boolean {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
}
