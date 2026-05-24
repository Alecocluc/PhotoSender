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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.appharbor.photosender.data.model.MediaFilter
import com.appharbor.photosender.data.model.MediaFolder
import com.appharbor.photosender.data.upload.SyncPlan
import com.appharbor.photosender.ui.components.EmptyState
import com.appharbor.photosender.ui.components.ScreenHeader
import com.appharbor.photosender.ui.components.SegmentedToggle
import com.appharbor.photosender.ui.theme.LocalExtendedColors
import com.appharbor.photosender.ui.theme.Spacing

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

    var searchQuery by remember { mutableStateOf("") }
    var hasPermission by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val displayFolders = remember(folders, searchQuery) {
        if (searchQuery.isBlank()) folders
        else folders.filter { it.bucketName.contains(searchQuery, ignoreCase = true) }
    }

    val context = LocalContext.current
    LaunchedEffect(syncState.summary) {
        syncState.summary?.let { summary ->
            Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
            viewModel.clearSyncSummary()
        }
    }

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
            contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(span = { GridItemSpan(2) }) {
                Column {
                    Spacer(Modifier.height(Spacing.sm))
                    ScreenHeader(
                        title = "Gallery",
                        subtitle = if (totalAssets > 0) "$totalAssets items · ${folders.size} folders"
                        else "Grant permission to browse media",
                    )
                    Spacer(Modifier.height(Spacing.md))

                    // Functional search
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                "Search folders…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(Spacing.md))

                    SegmentedToggle(
                        options = listOf(
                            MediaFilter.ALL to "All",
                            MediaFilter.PHOTOS to "Photos",
                            MediaFilter.VIDEOS to "Videos",
                        ),
                        selected = filter,
                        onSelect = { viewModel.setFilter(it) },
                        fillWidth = true,
                    )

                    if (totalAssets > 0) {
                        Spacer(Modifier.height(Spacing.md))
                        SegmentedToggle(
                            options = listOf(
                                UploadMode.ADD to "Add new",
                                UploadMode.SYNC to "Sync library",
                            ),
                            selected = uploadMode,
                            onSelect = { viewModel.setMode(it) },
                            fillWidth = true,
                        )
                        Spacer(Modifier.height(Spacing.md))

                        when (uploadMode) {
                            UploadMode.ADD -> {
                                val allSelected = viewModel.isAllMediaSelected()
                                FilledTonalButton(
                                    onClick = {
                                        if (allSelected) viewModel.deselectAll()
                                        else viewModel.selectAllMedia()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium,
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
                                    Spacer(Modifier.width(Spacing.sm))
                                    Text(
                                        text = if (allSelected) "Deselect All ($totalAssets)" else "Select All ($totalAssets)",
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                            UploadMode.SYNC -> {
                                FilledTonalButton(
                                    onClick = { viewModel.prepareSync() },
                                    enabled = !isPreparingSync,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium,
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
                                ) {
                                    Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(Spacing.sm))
                                    Text(
                                        text = if (isPreparingSync) "Checking…" else "Sync Library",
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                Text(
                                    text = "Uploads new photos · removes deleted ones from PC.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = Spacing.xs),
                                )
                            }
                        }
                        Spacer(Modifier.height(Spacing.sm))
                    }
                }
            }

            if (displayFolders.isEmpty() && searchQuery.isNotBlank()) {
                item(span = { GridItemSpan(2) }) {
                    EmptyState(
                        icon = Icons.Filled.Search,
                        title = "No folders found",
                        subtitle = "Try a different search term",
                        modifier = Modifier.padding(vertical = Spacing.xxl),
                    )
                }
            } else {
                if (displayFolders.isNotEmpty()) {
                    item(span = { GridItemSpan(2) }) {
                        FolderCard(
                            folder = displayFolders.first(),
                            featured = true,
                            onClick = { onFolderClick(displayFolders.first().bucketName) },
                        )
                    }
                }
                items(displayFolders.drop(1), key = { it.bucketName }) { folder ->
                    FolderCard(
                        folder = folder,
                        featured = false,
                        onClick = { onFolderClick(folder.bucketName) },
                    )
                }
            }

            item(span = { GridItemSpan(2) }) { Spacer(Modifier.height(80.dp)) }
        }

        // Transfer FAB — Add mode only
        if (uploadMode == UploadMode.ADD && selectedIds.isNotEmpty()) {
            val extColors = LocalExtendedColors.current
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = Spacing.lg, bottom = 80.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(extColors.buttonGradient)
                    .clickable {
                        viewModel.startTransfer()
                        onTransferClick()
                    }
                    .padding(horizontal = Spacing.xl, vertical = Spacing.md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color.White,
                    )
                    Spacer(Modifier.width(Spacing.sm))
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
private fun FolderCard(
    folder: MediaFolder,
    featured: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (featured) Modifier.height(200.dp) else Modifier.aspectRatio(1f))
            .clip(if (featured) MaterialTheme.shapes.large else MaterialTheme.shapes.medium)
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
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = if (featured) 0.6f else 0.55f)),
                        startY = if (featured) 80f else 60f,
                    )
                )
        )
        if (featured) {
            Icon(
                Icons.Filled.CameraAlt,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Spacing.md)
                    .size(26.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(Color.White.copy(alpha = 0.2f))
                    .padding(4.dp),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(if (featured) Spacing.lg else Spacing.md),
        ) {
            Text(
                text = folder.bucketName,
                style = if (featured) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${folder.itemCount} items",
                style = if (featured) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
            )
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
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "• Delete ${plan.deleteCount} file(s) from the desktop that you removed from this phone.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(if (plan.isNoOp) "OK" else "Sync") }
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
