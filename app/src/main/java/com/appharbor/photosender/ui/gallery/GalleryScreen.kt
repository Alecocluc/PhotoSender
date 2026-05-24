package com.appharbor.photosender.ui.gallery

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.appharbor.photosender.data.model.MediaFilter
import com.appharbor.photosender.data.model.MediaFolder
import com.appharbor.photosender.data.upload.SyncPlan
import com.appharbor.photosender.ui.theme.LocalExtendedColors
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults

@Composable
fun GalleryScreen(
    onFolderClick: (String) -> Unit,
    onTransferClick: () -> Unit,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val totalAssets by viewModel.totalAssetCount.collectAsStateWithLifecycle()
    val uploadMode by viewModel.uploadMode.collectAsStateWithLifecycle()
    val pendingSyncPlan by viewModel.pendingSyncPlan.collectAsStateWithLifecycle()
    val isPreparingSync by viewModel.isPreparingSync.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()

    var hasPermission by remember { mutableStateOf(false) }

    // Surface the desktop-deletion result of a finished sync as a toast.
    val context = LocalContext.current
    LaunchedEffect(syncState.summary) {
        syncState.summary?.let { summary ->
            Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
            viewModel.clearSyncSummary()
        }
    }

    // Show the upload/delete preview once a plan is computed.
    pendingSyncPlan?.let { plan ->
        SyncConfirmDialog(
            plan = plan,
            onConfirm = {
                val hasUploads = viewModel.confirmSync()
                if (hasUploads) onTransferClick()
            },
            onDismiss = { viewModel.cancelSync() },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermission = results.values.all { it }
        if (hasPermission) viewModel.loadFolders()
    }

    LaunchedEffect(Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header
            item(span = { GridItemSpan(2) }) {
                Column {
                    Text(
                        text = "Media Gallery",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (totalAssets > 0) "Browse and select your digital assets"
                        else "Grant permission to browse media",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Search bar placeholder
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Search folders or dates...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Filter chips
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val filters = listOf(
                            MediaFilter.ALL to "All",
                            MediaFilter.PHOTOS to "Photos",
                            MediaFilter.VIDEOS to "Videos",
                        )
                        items(filters.size) { idx ->
                            val (f, label) = filters[idx]
                            val selected = filter == f
                            val extColors = LocalExtendedColors.current
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .then(
                                        if (selected) Modifier.background(extColors.buttonGradient)
                                        else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    )
                                    .clickable { viewModel.setFilter(f) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // Mode toggle: Add new files (pick & upload) vs Sync (mirror the whole library).
                    if (totalAssets > 0) {
                        ModeToggle(
                            mode = uploadMode,
                            onModeChange = viewModel::setMode,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    when (uploadMode) {
                        // Add mode: pick files yourself; only new ones are uploaded.
                        UploadMode.ADD -> if (totalAssets > 0) {
                            val allSelected = viewModel.isAllMediaSelected()
                            FilledTonalButton(
                                onClick = {
                                    if (allSelected) {
                                        viewModel.deselectAll()
                                    } else {
                                        viewModel.selectAllMedia()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (allSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                            ) {
                                Icon(
                                    imageVector = if (allSelected) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (allSelected) "Deselect All ($totalAssets)"
                                    else "Select All Media ($totalAssets)",
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        // Sync mode: mirror the whole library — upload new, delete what's gone.
                        UploadMode.SYNC -> if (totalAssets > 0) {
                            FilledTonalButton(
                                onClick = { viewModel.prepareSync() },
                                enabled = !isPreparingSync,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Sync,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isPreparingSync) "Checking…" else "Sync Library",
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                text = "Uploads new photos and removes ones you deleted from this phone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }

            // Featured folder (first / Camera) — full width
            if (folders.isNotEmpty()) {
                val featured = folders.first()
                item(span = { GridItemSpan(2) }) {
                    FeaturedFolderCard(
                        folder = featured,
                        onClick = { onFolderClick(featured.bucketName) },
                    )
                }
            }

            // Remaining folders in 2-column grid
            if (folders.size > 1) {
                items(folders.drop(1)) { folder ->
                    FolderCard(
                        folder = folder,
                        onClick = { onFolderClick(folder.bucketName) },
                    )
                }
            }

            // Bottom space for FAB
            item(span = { GridItemSpan(2) }) {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }

        // Select All / Transfer FAB — Add mode only; Sync drives uploads from its own button.
        if (uploadMode == UploadMode.ADD && selectedIds.isNotEmpty()) {
            val extColors = LocalExtendedColors.current
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(extColors.buttonGradient)
                    .clickable {
                        viewModel.startTransfer()
                        onTransferClick()
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color.White,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Transfer ${selectedIds.size}",
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeaturedFolderCard(
    folder: MediaFolder,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = folder.coverUri,
            contentDescription = folder.bucketName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                        startY = 100f,
                    )
                )
        )
        // Camera icon badge
        Icon(
            Icons.Filled.CameraAlt,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.2f))
                .padding(4.dp),
        )
        // Title and count
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Text(
                text = folder.bucketName,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${folder.itemCount} items",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun FolderCard(
    folder: MediaFolder,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = folder.coverUri,
            contentDescription = folder.bucketName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                        startY = 80f,
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
        ) {
            Text(
                text = folder.bucketName,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${folder.itemCount} items",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun ModeToggle(
    mode: UploadMode,
    onModeChange: (UploadMode) -> Unit,
) {
    val extColors = LocalExtendedColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val segments = listOf(
            UploadMode.ADD to "Add new",
            UploadMode.SYNC to "Sync",
        )
        segments.forEach { (segment, label) ->
            val selected = mode == segment
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .then(
                        if (selected) Modifier.background(extColors.buttonGradient)
                        else Modifier
                    )
                    .clickable { onModeChange(segment) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun SyncConfirmDialog(
    plan: SyncPlan,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (plan.isNoOp) "Already in sync" else "Sync to desktop") },
        text = {
            if (plan.isNoOp) {
                Text("Your desktop already matches this phone — nothing to upload or remove.")
            } else {
                Column {
                    if (plan.uploadCount > 0) {
                        Text("• Upload ${plan.uploadCount} new file(s) (${formatBytes(plan.uploadBytes)}).")
                    }
                    if (plan.deleteCount > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "• Delete ${plan.deleteCount} file(s) from the desktop that you removed from this phone.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(if (plan.isNoOp) "OK" else "Sync")
            }
        },
        dismissButton = if (plan.isNoOp) null else {
            { TextButton(onClick = onDismiss) { Text("Cancel") } }
        },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
