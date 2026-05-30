package com.appharbor.pherry.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.ui.components.GradientProgressBar
import com.appharbor.pherry.ui.components.PrimaryButton
import com.appharbor.pherry.ui.components.SectionCard
import com.appharbor.pherry.ui.components.StatCard
import com.appharbor.pherry.ui.permissions.hasMediaPermission
import com.appharbor.pherry.ui.permissions.requiredMediaPermissions
import com.appharbor.pherry.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    onConnectClick: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenTransfers: () -> Unit,
    onOpenSettings: () -> Unit,
    onBeforeTransfer: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val serverName by viewModel.serverName.collectAsStateWithLifecycle()
    val transferState by viewModel.transferState.collectAsStateWithLifecycle()
    val completedCount by viewModel.completedCount.collectAsStateWithLifecycle()
    val failedCount by viewModel.failedCount.collectAsStateWithLifecycle()
    val queuedCount by viewModel.queuedCount.collectAsStateWithLifecycle()
    val lastBackupAt by viewModel.lastBackupAt.collectAsStateWithLifecycle()
    val autoBackupEnabled by viewModel.autoBackupEnabled.collectAsStateWithLifecycle()
    val autoBackupRequiresCharging by viewModel.autoBackupRequiresCharging.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val unsent by viewModel.unsent.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember { mutableStateOf(hasMediaPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> hasPermission = results.values.all { it } }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasPermission = hasMediaPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val connected = connectionState == ConnectionState.CONNECTED

    // Refresh the unsent count on open, on connect, on permission grant, and when a transfer ends.
    // The ViewModel's staleness guard keeps this from re-scanning a large library on every recomposition.
    LaunchedEffect(connectionState, hasPermission, transferState.isTransferring) {
        if (connected && hasPermission && !transferState.isTransferring) {
            viewModel.refreshUnsent()
        }
    }

    val headerSubtitle = when {
        connected && serverName.isNotBlank() -> "Linked with $serverName"
        connected -> "Linked with your desktop"
        connectionState == ConnectionState.CONNECTING -> "Connecting…"
        else -> "Not connected"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item {
            Spacer(Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Home",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = headerSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (connected && hasPermission) {
                    IconButton(
                        onClick = { viewModel.refreshUnsent(force = true) },
                        enabled = !unsent.isLoading,
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Re-check for new media",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.xs))
        }

        // ── Adaptive primary action ────────────────────────────────────────────
        item {
            when {
                !connected -> ActionHero(
                    icon = Icons.Filled.CloudOff,
                    iconTint = MaterialTheme.colorScheme.error,
                    title = if (connectionState == ConnectionState.CONNECTING) "Connecting…" else "Desktop not connected",
                    subtitle = "Pair with Pherry Desktop on your computer to start backing up.",
                    actionLabel = if (connectionState == ConnectionState.CONNECTING) null else "Connect desktop",
                    actionIcon = Icons.Filled.Computer,
                    onAction = onConnectClick,
                )

                !hasPermission -> ActionHero(
                    icon = Icons.Filled.PermMedia,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "Allow media access",
                    subtitle = "Pherry needs permission to read the photos and videos you back up.",
                    actionLabel = "Allow access",
                    actionIcon = Icons.Filled.PermMedia,
                    onAction = { permissionLauncher.launch(requiredMediaPermissions()) },
                )

                transferState.isTransferring -> LiveProgressCard(
                    progressPercent = transferState.progressPercent,
                    completedFiles = transferState.completedFiles,
                    totalFiles = transferState.totalFiles,
                    transferredBytes = transferState.transferredBytes,
                    totalBytes = transferState.totalBytes,
                    speedBytesPerSec = transferState.currentSpeedBytesPerSec,
                    etaSeconds = transferState.estimatedSecondsRemaining,
                    onView = onOpenTransfers,
                    formatBytes = viewModel::formatBytes,
                    formatSpeed = viewModel::formatSpeed,
                    formatTime = viewModel::formatTime,
                )

                unsent.isLoading && !unsent.computed -> ActionHero(
                    icon = Icons.Filled.CloudSync,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "Checking your library…",
                    subtitle = "Looking for photos that aren't on your desktop yet.",
                    actionLabel = null,
                    actionIcon = null,
                    onAction = {},
                    showSpinner = true,
                )

                unsent.count > 0 -> ActionHero(
                    icon = Icons.Filled.CloudUpload,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "${unsent.count} item${if (unsent.count == 1) "" else "s"} to back up",
                    subtitle = "${viewModel.formatBytes(unsent.bytes)} not yet on your desktop.",
                    actionLabel = "Back up now",
                    actionIcon = Icons.Filled.CloudUpload,
                    onAction = {
                        onBeforeTransfer()
                        viewModel.backUpNew(onQueued = onOpenTransfers)
                    },
                )

                else -> ActionHero(
                    icon = Icons.Filled.CheckCircle,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    title = "You're all caught up",
                    subtitle = "Every photo and video is backed up to your desktop.",
                    actionLabel = null,
                    actionIcon = null,
                    onAction = {},
                )
            }
        }

        // ── Failed retry ───────────────────────────────────────────────────────
        if (failedCount > 0 && !transferState.isTransferring) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                        .padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Text(
                        text = "$failedCount file${if (failedCount == 1) "" else "s"} failed to send.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { viewModel.retryFailed() },
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text("Retry", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // ── At-a-glance stats ────────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                StatCard(
                    icon = Icons.Filled.Schedule,
                    label = "Queued",
                    value = "$queuedCount",
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    icon = Icons.Filled.CloudDone,
                    label = "Backed up",
                    value = "$completedCount",
                    modifier = Modifier.weight(1f),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        // ── Auto-backup status ──────────────────────────────────────────────────
        item {
            SectionCard(title = "Auto-backup") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (autoBackupEnabled) Icons.Filled.CloudSync else Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = if (autoBackupEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (autoBackupEnabled) "On" else "Off",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (autoBackupEnabled) {
                                autoBackupConditions(wifiOnly, autoBackupRequiresCharging)
                            } else {
                                "Turn on to send new photos automatically."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!autoBackupEnabled) {
                        TextButton(onClick = onOpenSettings) { Text("Settings") }
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = "Last backup · ${formatRelativeTime(lastBackupAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── Browse library ───────────────────────────────────────────────────────
        item {
            OutlinedButton(
                onClick = onOpenLibrary,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text("Browse library", fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(Spacing.xs))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }

        item { Spacer(Modifier.height(Spacing.xxl)) }
    }
}

@Composable
private fun ActionHero(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    actionLabel: String?,
    actionIcon: ImageVector?,
    onAction: () -> Unit,
    showSpinner: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(iconTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                if (showSpinner) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                        color = iconTint,
                    )
                } else {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(26.dp))
                }
            }
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (actionLabel != null) {
            Spacer(Modifier.height(Spacing.lg))
            PrimaryButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                if (actionIcon != null) {
                    Icon(actionIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(actionLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun LiveProgressCard(
    progressPercent: Float,
    completedFiles: Int,
    totalFiles: Int,
    transferredBytes: Long,
    totalBytes: Long,
    speedBytesPerSec: Long,
    etaSeconds: Long,
    onView: () -> Unit,
    formatBytes: (Long) -> String,
    formatSpeed: (Long) -> String,
    formatTime: (Long) -> String,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progressPercent,
        animationSpec = tween(300),
        label = "home_progress",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(Spacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(
                    text = "Backing up…",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "$completedFiles / $totalFiles files",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${(animatedProgress * 100).toInt()}%",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        GradientProgressBar(progress = animatedProgress, height = 6.dp, animated = true)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = buildString {
                append("${formatBytes(transferredBytes)} / ${formatBytes(totalBytes)}")
                if (speedBytesPerSec > 0) append(" · ${formatSpeed(speedBytesPerSec)}")
                val eta = formatTime(etaSeconds)
                if (eta.isNotEmpty()) append(" · $eta")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.md))
        OutlinedButton(
            onClick = onView,
            modifier = Modifier.align(Alignment.End),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Text("View transfers", style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun autoBackupConditions(wifiOnly: Boolean, requiresCharging: Boolean): String {
    val parts = buildList {
        add(if (wifiOnly) "over Wi-Fi" else "on any network")
        if (requiresCharging) add("while charging")
    }
    return "Runs " + parts.joinToString(" ") + " · checks every 15 min"
}

private fun formatRelativeTime(timestamp: Long): String {
    if (timestamp <= 0) return "No backups yet"
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> "Just now"
        diff < 3_600_000L -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        diff < 172_800_000L -> "Yesterday"
        else -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
