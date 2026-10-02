package com.appharbor.pherry.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.data.model.MediaItem
import com.appharbor.pherry.ui.components.EmptyStrip
import com.appharbor.pherry.ui.components.FilmRow
import com.appharbor.pherry.ui.components.Fmt
import com.appharbor.pherry.ui.components.Frame
import com.appharbor.pherry.ui.components.FrameNumber
import com.appharbor.pherry.ui.components.LocalSnackbarHost
import com.appharbor.pherry.ui.components.Ph
import com.appharbor.pherry.ui.components.PhIcon
import com.appharbor.pherry.ui.components.SelectionTicket
import com.appharbor.pherry.ui.theme.PherryShape
import com.appharbor.pherry.ui.theme.PherryTheme
import com.appharbor.pherry.ui.theme.Spacing
import kotlinx.coroutines.launch

/** Paper between rows of the contact sheet. */
private val RowGap = 10.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderDetailScreen(
    bucketName: String,
    onBack: () -> Unit,
    onTransferClick: () -> Unit,
    onConnectClick: () -> Unit,
    connectionState: ConnectionState,
    onBeforeTransfer: () -> Unit = {},
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val loadedName by viewModel.currentFolderName.collectAsStateWithLifecycle()
    val folderItems by viewModel.currentFolderItems.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val queueing by viewModel.isQueueing.collectAsStateWithLifecycle()
    val selectedBytes by viewModel.selectedBytes.collectAsStateWithLifecycle()
    val completedIds by viewModel.completedIds.collectAsStateWithLifecycle()
    val serverName by viewModel.serverName.collectAsStateWithLifecycle()

    val c = PherryTheme.colors
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    var viewerId by rememberSaveable { mutableStateOf<Long?>(null) }
    var ticketHeight by remember { mutableIntStateOf(0) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, bucketName) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.loadFolderItems(bucketName)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val loading = loadedName != bucketName
    val items = if (loading) emptyList<MediaItem>() else folderItems
    val connected = connectionState == ConnectionState.CONNECTED
    val selecting = selectionMode || selectedIds.isNotEmpty()

    val viewerIndex = remember(viewerId, items) {
        viewerId?.let { id -> items.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
    }
    val viewerOpen = viewerIndex != null
    val unsent = remember(items, completedIds) { items.filter { it.id !in completedIds } }
    val allSelected = remember(items, selectedIds) { items.isNotEmpty() && items.all { it.id in selectedIds } }
    val unsentAllSelected = remember(unsent, selectedIds) { unsent.all { it.id in selectedIds } }
    // Selection keeps insertion order, so each frame's ring carries the order it was picked in.
    val pickOrder = remember(selectedIds) {
        HashMap<Long, Int>(selectedIds.size).apply { selectedIds.forEachIndexed { i, id -> put(id, i + 1) } }
    }

    BackHandler(enabled = selecting && !viewerOpen) { selectionMode = false; viewModel.deselectAll() }

    val dataLine = when {
        selecting -> "${Fmt.count(selectedIds.size)} selected"
        loading -> null
        else -> listOf(
            Fmt.plural(items.size, "item"),
            if (unsent.isNotEmpty()) "${Fmt.count(unsent.size)} not backed up" else "all backed up",
        ).joinToString(" · ")
    }

    val alreadyThere = remember(selectedIds, completedIds) { selectedIds.count { it in completedIds } }

    LaunchedEffect(viewModel) {
        viewModel.sendEvents.collect { outcome ->
            when {
                outcome.error != null -> snackbar.showSnackbar(outcome.error)
                outcome.queued > 0 -> { viewerId = null; selectionMode = false; onTransferClick() }
                else -> snackbar.showSnackbar(alreadySentMessage(outcome.alreadySent, serverName))
            }
        }
    }
    val sendSelection: () -> Unit = { onBeforeTransfer(); viewModel.startTransfer() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(c.paper),
    ) {
        val columns = when {
            maxWidth >= 840.dp -> 6
            maxWidth >= 600.dp -> 4
            else -> 3
        }
        val rows = remember(items, columns) { items.chunked(columns) }
        val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // A hidden ticket reports no size change, so only count its height while it is up.
        val listBottom = if (selectedIds.isNotEmpty() && ticketHeight > 0) with(density) { ticketHeight.toDp() } else navigationBottom

        // Hidden from accessibility while the viewer covers it.
        Column(
            Modifier
                .fillMaxSize()
                .then(if (viewerOpen) Modifier.clearAndSetSemantics { } else Modifier),
        ) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = bucketName,
                            style = MaterialTheme.typography.titleLarge,
                            color = c.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (dataLine != null) {
                            Text(
                                text = dataLine.uppercase(),
                                style = PherryTheme.text.monoCaps,
                                color = c.ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        PhIcon(Ph.ArrowLeft, contentDescription = "Back", tint = c.ink)
                    }
                },
                actions = {
                    if (items.isNotEmpty()) {
                        TextButton(onClick = {
                            selectionMode = !selecting
                            if (!selectionMode) viewModel.deselectAll()
                        }) { Text(if (selecting) "Done" else "Select", color = c.ink) }
                    }
                    if (selecting && unsent.isNotEmpty() && !unsentAllSelected) {
                        TextButton(onClick = { viewModel.selectUnsent(items) }) {
                            Text("Select new", style = MaterialTheme.typography.labelLarge, color = c.ink)
                        }
                    }
                    if (selecting && items.isNotEmpty()) {
                        IconToggleButton(
                            checked = allSelected,
                            onCheckedChange = { all -> if (all) viewModel.selectAll(items) else viewModel.deselectAll() },
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(PherryShape.print)
                                    .background(if (allSelected) c.envelope else Color.Transparent),
                                contentAlignment = Alignment.Center,
                            ) {
                                PhIcon(
                                    Ph.SelectAll,
                                    contentDescription = if (allSelected) "Clear selection" else "Select all",
                                    tint = if (allSelected) c.onEnvelope else c.ink,
                                    size = 22.dp,
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = c.paper,
                    scrolledContainerColor = c.paper,
                    titleContentColor = c.ink,
                    navigationIconContentColor = c.ink,
                    actionIconContentColor = c.ink,
                ),
            )

            when {
                loading -> LoadingSheet(columns = columns)

                items.isEmpty() -> EmptyStrip(
                    title = "This album is empty",
                    body = "Photos and videos saved to $bucketName on this phone appear here.",
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Spacing.sm,
                        end = Spacing.sm,
                        top = Spacing.xs,
                        bottom = listBottom + Spacing.lg,
                    ),
                    verticalArrangement = Arrangement.spacedBy(RowGap),
                ) {
                    itemsIndexed(rows, key = { _, row -> row.first().id }) { rowIndex, row ->
                        val first = rowIndex * columns
                        FilmRow(
                            count = row.size,
                            columns = columns,
                            edgeTop = { i -> FrameNumber(number = first + i + 1) },
                            edgeBottom = { i -> if (row[i].id in completedIds) SavedMark() },
                        ) { i ->
                            val item = row[i]
                            ContactFrame(
                                item = item,
                                number = first + i + 1,
                                total = items.size,
                                pickNumber = pickOrder[item.id],
                                backedUp = item.id in completedIds,
                                selecting = selecting,
                                onClick = {
                                    if (selecting) viewModel.toggleSelection(item.id) else viewerId = item.id
                                },
                                onLongClick = {
                                    if (item.id !in selectedIds) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.toggleSelection(item.id)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        val ticketCount = heldWhile(selecting, selectedIds.size)
        val ticketBytes = heldWhile(selecting, selectedBytes)
        val ticketAlreadyThere = heldWhile(selecting, alreadyThere)
        AnimatedVisibility(
            visible = selectedIds.isNotEmpty() && !viewerOpen,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { ticketHeight = it.height },
        ) {
            SelectionTicket(
                count = ticketCount,
                detail = ticketDetail(ticketBytes, connected, serverName, ticketAlreadyThere),
                actionLabel = if (connected) { if (queueing) "Preparing..." else sendLabel(ticketCount, ticketAlreadyThere) } else null,
                actionEnabled = !queueing,
                onAction = { sendSelection() },
                onClear = { viewModel.deselectAll(); selectionMode = false },
                hint = if (connected) null else "Pair a computer",
                onHint = onConnectClick,
                applyNavigationPadding = true,
            )
        }

        val shownViewerIndex = heldWhile(viewerOpen, viewerIndex ?: 0)
        AnimatedVisibility(
            visible = viewerOpen,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140)),
        ) {
            AlbumViewer(
                items = items,
                startIndex = shownViewerIndex,
                completedIds = completedIds,
                connected = connected,
                onPageChange = { id -> if (viewerId != null) viewerId = id },
                onClose = { viewerId = null },
                onSend = { item ->
                    viewModel.selectAll(listOf(item))
                    // Stay on the picture when the computer already has it; the snackbar says so.
                    sendSelection()
                },
                onConnect = onConnectClick,
            )
        }
    }
}

/** One frame on the contact sheet: tap opens it (or toggles it while selecting), long-press starts selecting. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactFrame(
    item: MediaItem,
    number: Int,
    total: Int,
    pickNumber: Int?,
    backedUp: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val description = buildString {
        append(item.displayName)
        append(if (item.isVideo) ", video" else ", photo")
        if (backedUp) append(", backed up")
        // The edge-print number above the frame is hidden from TalkBack, so say it here.
        append(", frame ${Fmt.count(number)} of ${Fmt.count(total)}")
    }
    Frame(
        model = rememberFrameModel(item.uri, item.isVideo),
        contentDescription = description,
        isVideo = item.isVideo,
        selectedNumber = pickNumber,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { selected = pickNumber != null }
            .combinedClickable(
                onClickLabel = when {
                    !selecting -> "Open"
                    pickNumber != null -> "Deselect"
                    else -> "Select"
                },
                onLongClickLabel = if (pickNumber == null) "Select" else null,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
    )
}

/** Unexposed rows while the album is read: film and blank frames, no spinners. */
@Composable
private fun LoadingSheet(columns: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            .semantics { contentDescription = "Loading album" },
        verticalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        repeat(4) {
            FilmRow(count = columns, columns = columns) {
                BlankFrame(aspectRatio = 1f)
            }
        }
    }
}
