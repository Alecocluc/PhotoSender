package com.appharbor.pherry.ui.gallery

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.data.model.MediaFilter
import com.appharbor.pherry.data.model.MediaFolder
import com.appharbor.pherry.data.upload.SyncPlan
import com.appharbor.pherry.ui.components.EmptyState
import com.appharbor.pherry.ui.components.GradientButton
import com.appharbor.pherry.ui.components.ScreenHeader
import com.appharbor.pherry.ui.components.SegmentedToggle
import com.appharbor.pherry.ui.theme.LocalExtendedColors
import com.appharbor.pherry.ui.theme.Spacing

@Composable
fun GalleryScreen(
    onFolderClick: (String) -> Unit,
    onTransferClick: () -> Unit,
    onConnectClick: () -> Unit,
    onBeforeTransfer: () -> Unit = {},
    connectionState: ConnectionState,
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
    val confirmDestructiveSync by viewModel.confirmDestructiveSync.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(hasMediaPermission(context)) }
    val focusManager = LocalFocusManager.current

    val displayFolders = remember(folders, searchQuery) {
        if (searchQuery.isBlank()) folders
        else folders.filter { it.bucketName.contains(searchQuery, ignoreCase = true) }
    }

    LaunchedEffect(syncState.summary) {
        syncState.summary?.let { summary ->
            Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
            viewModel.clearSyncSummary()
        }
    }

    LaunchedEffect(connectionState, hasPermission) {
        if (connectionState == ConnectionState.CONNECTED && hasPermission) {
            viewModel.loadFolders()
        }
    }

    pendingSyncPlan?.let { plan ->
        SyncConfirmDialog(
            plan = plan,
            confirmDestructive = confirmDestructiveSync,
            onConfirm = {
                if (!plan.isNoOp) onBeforeTransfer()
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

    fun requestMediaPermission() {
        permissionLauncher.launch(requiredMediaPermissions())
    }

    fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = hasMediaPermission(context)
                hasPermission = granted
                if (granted) viewModel.loadFolders()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Spacer(Modifier.height(Spacing.sm))
                    ScreenHeader(
                        title = "Gallery",
                        subtitle = when {
                            connectionState != ConnectionState.CONNECTED -> "Pair with desktop to start"
                            !hasPermission -> "Allow media access after pairing"
                            totalAssets > 0 -> "$totalAssets items · ${folders.size} folders"
                            else -> "Ready to browse media"
                        },
                    )
                    Spacer(Modifier.height(Spacing.md))

                    if (connectionState != ConnectionState.CONNECTED) {
                        PairingSetupCard(
                            connectionState = connectionState,
                            onConnectClick = onConnectClick,
                        )
                        Spacer(Modifier.height(Spacing.md))
                    } else if (!hasPermission) {
                        MediaPermissionCard(
                            onAllowClick = ::requestMediaPermission,
                            onSettingsClick = ::openAppSettings,
                        )
                        Spacer(Modifier.height(Spacing.md))
                    }

                    if (connectionState != ConnectionState.CONNECTED || !hasPermission) {
                        return@Column
                    }

                    // Filter row with inline search toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        SegmentedToggle(
                            options = listOf(
                                MediaFilter.ALL to "All",
                                MediaFilter.PHOTOS to "Photos",
                                MediaFilter.VIDEOS to "Videos",
                            ),
                            selected = filter,
                            onSelect = { viewModel.setFilter(it) },
                            fillWidth = true,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(
                                    if (showSearch || searchQuery.isNotEmpty())
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                                .clickable {
                                    if (showSearch) {
                                        showSearch = false
                                        searchQuery = ""
                                        focusManager.clearFocus()
                                    } else {
                                        showSearch = true
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (showSearch || searchQuery.isNotEmpty()) Icons.Filled.Close else Icons.Filled.Search,
                                contentDescription = if (showSearch) "Close search" else "Search folders",
                                tint = if (showSearch || searchQuery.isNotEmpty())
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    // Collapsible search field
                    AnimatedVisibility(
                        visible = showSearch,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Column {
                            Spacer(Modifier.height(Spacing.sm))
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
                        }
                    }

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
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        icon = Icons.Filled.Search,
                        title = "No folders found",
                        subtitle = "Try a different search term",
                        modifier = Modifier.padding(vertical = Spacing.xxl),
                    )
                }
            } else {
                if (connectionState == ConnectionState.CONNECTED && hasPermission && displayFolders.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(
                            icon = Icons.Filled.PermMedia,
                            title = "No media found",
                            subtitle = "Photos and videos you add to this device will appear here",
                            modifier = Modifier.padding(vertical = Spacing.xxl),
                        )
                    }
                } else if (connectionState == ConnectionState.CONNECTED && hasPermission && displayFolders.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
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

            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(Spacing.xxl)) }
        }

        // Transfer FAB — Add mode only
        if (uploadMode == UploadMode.ADD && selectedIds.isNotEmpty()) {
            val extColors = LocalExtendedColors.current
            ExtendedFloatingActionButton(
                onClick = {
                    onBeforeTransfer()
                    viewModel.startTransfer()
                    onTransferClick()
                },
                icon = {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                    )
                },
                text = {
                    Text(
                        "Transfer ${selectedIds.size}",
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
                containerColor = Color.Transparent,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = Spacing.lg, bottom = Spacing.lg)
                    .background(extColors.buttonGradient, MaterialTheme.shapes.extraLarge),
            )
        }
    }
}

@Composable
private fun PairingSetupCard(
    connectionState: ConnectionState,
    onConnectClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Computer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(42.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f))
                    .padding(9.dp),
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Pair with your desktop",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Scan the QR code shown in Pherry Desktop before choosing media.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(Spacing.md))
        GradientButton(
            onClick = onConnectClick,
            enabled = connectionState != ConnectionState.CONNECTING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Filled.Wifi,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color.White,
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = if (connectionState == ConnectionState.CONNECTING) "Connecting…" else "Connect desktop",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun MediaPermissionCard(
    onAllowClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(Spacing.lg),
    ) {
        Text(
            text = "Allow media access",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "Pherry only reads local photos and videos you choose to send.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilledTonalButton(onClick = onAllowClick, shape = MaterialTheme.shapes.medium) {
                Text("Allow media")
            }
            TextButton(onClick = onSettingsClick) {
                Text("Open settings")
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
            .clickable(role = Role.Button, onClick = onClick),
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
                maxLines = if (featured) 2 else 1,
                overflow = TextOverflow.Ellipsis,
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
    confirmDestructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(plan, confirmDestructive) {
        if (!confirmDestructive && plan.deleteCount == 0 && !plan.isNoOp) {
            onConfirm()
        }
    }
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
            when {
                plan.isNoOp -> TextButton(onClick = onConfirm) { Text("OK") }
                plan.deleteCount > 0 -> FilledTonalButton(
                    onClick = onConfirm,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) { Text("Sync & Delete") }
                else -> TextButton(onClick = onConfirm) { Text("Sync") }
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

private fun requiredMediaPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun hasMediaPermission(context: Context): Boolean {
    return requiredMediaPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
