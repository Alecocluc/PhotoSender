package com.appharbor.pherry.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.appharbor.pherry.ui.theme.LocalExtendedColors
import com.appharbor.pherry.ui.theme.Spacing

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderDetailScreen(
    bucketName: String,
    onBack: () -> Unit,
    onTransferClick: () -> Unit,
    onBeforeTransfer: () -> Unit = {},
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val items by viewModel.currentFolderItems.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    var previewItem by remember { mutableStateOf<com.appharbor.pherry.data.model.MediaItem?>(null) }

    LaunchedEffect(bucketName) {
        viewModel.loadFolderItems(bucketName)
    }

    previewItem?.let { item ->
        AlertDialog(
            onDismissRequest = { previewItem = null },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { previewItem = null }) {
                    Text("Close")
                }
            },
            title = {
                Text(
                    text = item.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                AsyncImage(
                    model = item.uri,
                    contentDescription = item.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clip(MaterialTheme.shapes.medium),
                )
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 108.dp),
            contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.sm, end = Spacing.md, top = Spacing.sm),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = bucketName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (selectedIds.isEmpty()) "${items.size} items" else "${selectedIds.size} selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selectedIds.isNotEmpty()) {
                        IconButton(onClick = { viewModel.deselectAll() }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Clear selection",
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            if (viewModel.isAllSelected(items)) viewModel.deselectAll()
                            else viewModel.selectAll(items)
                        }
                    ) {
                        Icon(
                            Icons.Filled.SelectAll,
                            contentDescription = "Select All",
                            tint = if (viewModel.isAllSelected(items))
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(items, key = { it.id }) { item ->
                val isSelected = item.id in selectedIds
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .combinedClickable(
                            role = Role.Button,
                            onClick = {
                                if (selectedIds.isNotEmpty()) viewModel.toggleSelection(item.id)
                                else previewItem = item
                            },
                            onLongClick = { viewModel.toggleSelection(item.id) },
                        )
                        .then(
                            if (isSelected) Modifier.border(
                                2.dp,
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.shapes.extraSmall,
                            ) else Modifier
                        )
                ) {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .then(
                                if (isSelected)
                                    Modifier.background(MaterialTheme.colorScheme.primary)
                                else
                                    Modifier
                                        .background(Color.Black.copy(alpha = 0.22f))
                                        .border(1.5.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(Spacing.xxl)) }
        }

        if (selectedIds.isNotEmpty()) {
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
