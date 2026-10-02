package com.appharbor.pherry.ui.gallery

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.data.model.MediaFilter
import com.appharbor.pherry.ui.components.EdgeText
import com.appharbor.pherry.ui.components.EmptyStrip
import com.appharbor.pherry.ui.components.FilmRow
import com.appharbor.pherry.ui.components.Fmt
import com.appharbor.pherry.ui.components.Frame
import com.appharbor.pherry.ui.components.FrameNumber
import com.appharbor.pherry.ui.components.LocalSnackbarHost
import com.appharbor.pherry.ui.components.Ph
import com.appharbor.pherry.ui.components.PhIcon
import com.appharbor.pherry.ui.components.PrintButton
import com.appharbor.pherry.ui.components.PrintButtonStyle
import com.appharbor.pherry.ui.components.PrintSegmented
import com.appharbor.pherry.ui.components.ScreenHeader
import com.appharbor.pherry.ui.components.SelectionTicket
import com.appharbor.pherry.ui.components.StatusTag
import com.appharbor.pherry.ui.components.TagKind
import com.appharbor.pherry.ui.permissions.hasMediaPermission
import com.appharbor.pherry.ui.permissions.MediaAccess
import com.appharbor.pherry.ui.permissions.mediaAccess
import com.appharbor.pherry.ui.components.Notice
import com.appharbor.pherry.ui.permissions.rememberPermissionAsk
import com.appharbor.pherry.ui.permissions.requiredMediaPermissions
import com.appharbor.pherry.ui.theme.PherryShape
import com.appharbor.pherry.ui.theme.PherryTheme
import com.appharbor.pherry.ui.theme.Spacing
import kotlinx.coroutines.launch

/** Library frames print at 4:3, like the strip of recent sends on Home. */
private const val StripAspect = 4f / 3f

@Composable
fun GalleryScreen(
    onFolderClick: (String) -> Unit,
    onTransferClick: () -> Unit,
    onConnectClick: () -> Unit,
    onBeforeTransfer: () -> Unit = {},
    connectionState: ConnectionState,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val queueing by viewModel.isQueueing.collectAsStateWithLifecycle()
    val selectedBytes by viewModel.selectedBytes.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val totalAssets by viewModel.totalAssetCount.collectAsStateWithLifecycle()
    val unsentCount by viewModel.unsentCount.collectAsStateWithLifecycle()
    val libraryLoaded by viewModel.libraryLoaded.collectAsStateWithLifecycle()
    val quickPicks by viewModel.quickPicks.collectAsStateWithLifecycle()
    val serverName by viewModel.serverName.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val completedIds by viewModel.completedIds.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbar = LocalSnackbarHost.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember { mutableStateOf(hasMediaPermission(context)) }
    var access by remember { mutableStateOf(mediaAccess(context)) }
    var permissionBlocked by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var ticketHeight by remember { mutableIntStateOf(0) }

    val mediaAsk = rememberPermissionAsk(*requiredMediaPermissions())
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermission = hasMediaPermission(context)
        access = mediaAccess(context)
        // Only a real "don't ask again" swaps Allow for app settings; a dismissed dialog asks again.
        permissionBlocked = !hasPermission && mediaAsk.blockedAfterDenial()
        if (hasPermission) viewModel.loadFolders()
    }

    // Browsing needs only media permission. Re-read on every return so new photos and finished
    // backups show up; the ViewModel cancels a read that is still running.
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = hasMediaPermission(context)
                access = mediaAccess(context)
                if (hasPermission) {
                    permissionBlocked = false
                    viewModel.loadFolders()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Clearing the summary changes this effect's key and cancels it, so the snackbar is shown from
    // the screen's scope; otherwise it would be dismissed the frame after it appears.
    LaunchedEffect(syncState.summary) {
        val summary = syncState.summary ?: return@LaunchedEffect
        viewModel.clearSyncSummary()
        scope.launch { snackbar.showSnackbar(summary) }
    }

    fun closeSearch() {
        searchOpen = false
        query = ""
        focusManager.clearFocus()
    }

    BackHandler(enabled = selectedIds.isNotEmpty()) { viewModel.deselectAll() }
    BackHandler(enabled = searchOpen && selectedIds.isEmpty()) { closeSearch() }

    val connected = connectionState == ConnectionState.CONNECTED
    val selecting = selectedIds.isNotEmpty()
    // Selection keeps insertion order, so each frame's ring carries the order it was picked in.
    val pickOrder = remember(selectedIds) {
        HashMap<Long, Int>(selectedIds.size).apply { selectedIds.forEachIndexed { i, id -> put(id, i + 1) } }
    }
    val alreadyThere = remember(selectedIds, completedIds) { selectedIds.count { it in completedIds } }
    val shownAlbums = remember(albums, query) {
        val q = query.trim()
        if (q.isEmpty()) albums else albums.filter { it.name.contains(q, ignoreCase = true) }
    }
    val noun = when (filter) {
        MediaFilter.ALL -> "item"
        MediaFilter.PHOTOS -> "photo"
        MediaFilter.VIDEOS -> "video"
    }
    val headerData = if (hasPermission && libraryLoaded && totalAssets > 0) {
        listOf(
            Fmt.plural(totalAssets, noun),
            Fmt.plural(albums.size, "album"),
            if (unsentCount > 0) "${Fmt.count(unsentCount)} not backed up" else "all backed up",
        ).joinToString(" · ")
    } else {
        null
    }
    // Filters stay reachable while a filter switch is loading; only a truly empty library hides them.
    val showControls = hasPermission && !(libraryLoaded && totalAssets == 0 && filter == MediaFilter.ALL)

    LaunchedEffect(viewModel) {
        viewModel.sendEvents.collect { outcome ->
            when {
                outcome.error != null -> snackbar.showSnackbar(outcome.error)
                outcome.queued > 0 -> onTransferClick()
                else -> snackbar.showSnackbar(alreadySentMessage(outcome.alreadySent, serverName))
            }
        }
    }
    val send: () -> Unit = { onBeforeTransfer(); viewModel.startTransfer() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 600.dp) 6 else 4
        // A hidden ticket reports no size change, so only count its height while it is up.
        val ticketSpace = if (selecting) with(density) { ticketHeight.toDp() } else 0.dp
        val bottomPadding = Spacing.xxl + ticketSpace

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "header") {
                ScreenHeader(title = "Library", data = headerData)
            }

            if (!hasPermission) {
                item(key = "permission") {
                    PermissionBlock(
                        blocked = permissionBlocked,
                        onAllow = {
                            mediaAsk.beforeLaunch()
                            permissionLauncher.launch(requiredMediaPermissions())
                        },
                        onOpenSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            )
                        },
                    )
                }
                return@LazyColumn
            }

            if (showControls) {
                if (access == MediaAccess.SELECTED) {
                    item(key = "limited-access") {
                        Notice(
                            title = "Showing photos you allowed",
                            detail = "Other photos stay private. Choose more photos or allow full access for whole-library backup.",
                            actionLabel = "Manage",
                            onAction = { mediaAsk.beforeLaunch(); permissionLauncher.launch(requiredMediaPermissions()) },
                            error = false,
                        )
                    }
                }
                item(key = "controls") {
                    LibraryControls(
                        filter = filter,
                        onFilter = viewModel::setFilter,
                        searchOpen = searchOpen,
                        query = query,
                        onQueryChange = { query = it },
                        onToggleSearch = { if (searchOpen) closeSearch() else searchOpen = true },
                        onDone = { focusManager.clearFocus() },
                    )
                }
            }

            if (!libraryLoaded) {
                item(key = "loading") { LoadingStrips(columns = columns) }
                return@LazyColumn
            }

            if (totalAssets > 0) {
                item(key = "picks") {
                    QuickPickRow(
                        picks = quickPicks,
                        selectedIds = selectedIds,
                        onNew = viewModel::selectNewSinceBackup,
                        onRecent = { viewModel.selectRecent(GalleryViewModel.RECENT_DAYS) },
                        onEverything = viewModel::selectAllMedia,
                        onUnpick = viewModel::deselect,
                    )
                }
            }

            when {
                totalAssets == 0 && filter == MediaFilter.ALL -> item(key = "empty") {
                    EmptyStrip(
                        title = "No photos or videos yet",
                        body = "Photos and videos you take or save on this phone appear here, one strip per album.",
                    )
                }

                totalAssets == 0 -> item(key = "empty-filter") {
                    EmptyStrip(
                        title = if (filter == MediaFilter.VIDEOS) "No videos on this phone" else "No photos on this phone",
                        body = "Switch to All to see everything in your library.",
                        action = {
                            PrintButton("Show everything", onClick = { viewModel.setFilter(MediaFilter.ALL) }, style = PrintButtonStyle.Outline)
                        },
                    )
                }

                shownAlbums.isEmpty() -> item(key = "no-match") {
                    EmptyStrip(
                        title = "No album called “${query.trim()}”",
                        body = "Check the spelling, or clear the search to see every album.",
                        action = {
                            PrintButton("Clear search", onClick = ::closeSearch, style = PrintButtonStyle.Outline)
                        },
                    )
                }

                // Prefixed so an album called "header" or "picks" can't collide with the fixed items.
                else -> items(shownAlbums, key = { "album:" + it.name }) { album ->
                    AlbumStrip(
                        album = album,
                        columns = columns,
                        selectedIds = selectedIds,
                        pickOrder = pickOrder,
                        onClick = { onFolderClick(album.name) },
                    )
                }
            }
        }

        val ticketCount = heldWhile(selecting, selectedIds.size)
        val ticketBytes = heldWhile(selecting, selectedBytes)
        val ticketAlreadyThere = heldWhile(selecting, alreadyThere)
        AnimatedVisibility(
            visible = selecting,
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
                onAction = send,
                onClear = viewModel::deselectAll,
                hint = if (connected) null else "Pair a computer",
                onHint = onConnectClick,
                // The app's navigation bar sits below this screen and already clears the system bar.
                applyNavigationPadding = false,
            )
        }
    }
}

// ── Permission ───────────────────────────────────────────────────────────────

@Composable
private fun PermissionBlock(blocked: Boolean, onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    if (blocked) {
        EmptyStrip(
            title = "Photo access is off",
            body = "Android won't ask again. Open Pherry's settings, choose Permissions, then Photos and videos, and allow access.",
            action = { PrintButton("Open app settings", onClick = onOpenSettings, icon = Ph.Gear) },
        )
    } else {
        EmptyStrip(
            title = "Allow photo access",
            body = "Pherry reads the photos and videos on this phone so you can pick what to send. Nothing leaves the phone until you send it.",
            action = { PrintButton("Allow access", onClick = onAllow, icon = Ph.Image) },
        )
    }
}

// ── Controls ─────────────────────────────────────────────────────────────────

@Composable
private fun LibraryControls(
    filter: MediaFilter,
    onFilter: (MediaFilter) -> Unit,
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onDone: () -> Unit,
) {
    val c = PherryTheme.colors
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(searchOpen) {
        // The field is composed in the same pass that opens it; focus may still race on restore.
        if (searchOpen) runCatching { focusRequester.requestFocus() }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PrintSegmented(
                options = listOf(
                    MediaFilter.ALL to "All",
                    MediaFilter.PHOTOS to "Photos",
                    MediaFilter.VIDEOS to "Videos",
                ),
                selected = filter,
                onSelect = onFilter,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.xs))
            IconButton(onClick = onToggleSearch, modifier = Modifier.size(Spacing.touch)) {
                PhIcon(
                    icon = if (searchOpen) Ph.X else Ph.Search,
                    contentDescription = if (searchOpen) "Close search" else "Search albums",
                    tint = c.ink,
                )
            }
        }
        AnimatedVisibility(
            visible = searchOpen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm)
                    .focusRequester(focusRequester),
                textStyle = MaterialTheme.typography.bodyLarge,
                // A label keeps the field named for TalkBack once a query is typed.
                label = { Text("Search albums") },
                placeholder = { Text("Album name", style = MaterialTheme.typography.bodyLarge) },
                leadingIcon = { PhIcon(Ph.Search, contentDescription = null, size = 20.dp) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            PhIcon(Ph.XBold, contentDescription = "Clear search", size = 18.dp)
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onDone() }),
                shape = PherryShape.print,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = c.ink,
                    unfocusedTextColor = c.ink,
                    focusedContainerColor = c.sheet,
                    unfocusedContainerColor = c.sheet,
                    cursorColor = c.ink,
                    selectionColors = TextSelectionColors(handleColor = c.ink, backgroundColor = c.envelope.copy(alpha = 0.45f)),
                    focusedBorderColor = c.ink,
                    unfocusedBorderColor = c.ink3,
                    focusedLeadingIconColor = c.ink2,
                    unfocusedLeadingIconColor = c.ink2,
                    focusedTrailingIconColor = c.ink,
                    unfocusedTrailingIconColor = c.ink,
                    focusedLabelColor = c.ink2,
                    unfocusedLabelColor = c.ink3,
                    focusedPlaceholderColor = c.ink3,
                    unfocusedPlaceholderColor = c.ink3,
                ),
            )
        }
    }
}

// ── Quick picks ──────────────────────────────────────────────────────────────

@Composable
private fun QuickPickRow(
    picks: QuickPicks,
    selectedIds: Set<Long>,
    onNew: () -> Unit,
    onRecent: () -> Unit,
    onEverything: () -> Unit,
    onUnpick: (Set<Long>) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (picks.newIds.isNotEmpty()) {
            QuickPick(
                label = "Not backed up (${Fmt.count(picks.newIds.size)})",
                icon = Ph.Sparkle,
                ids = picks.newIds,
                selectedIds = selectedIds,
                onPick = onNew,
                onUnpick = onUnpick,
            )
        }
        if (picks.recentIds.isNotEmpty()) {
            QuickPick(
                label = "Last ${GalleryViewModel.RECENT_DAYS} days",
                icon = Ph.Calendar,
                ids = picks.recentIds,
                selectedIds = selectedIds,
                onPick = onRecent,
                onUnpick = onUnpick,
            )
        }
        QuickPick(
            label = "Everything (${Fmt.count(picks.allIds.size)})",
            icon = Ph.SelectAll,
            ids = picks.allIds,
            selectedIds = selectedIds,
            onPick = onEverything,
            onUnpick = onUnpick,
        )
    }
}

/** A pick adds its frames to the selection; once they are all in, tapping it again takes them out. */
@Composable
private fun QuickPick(
    label: String,
    @DrawableRes icon: Int,
    ids: Set<Long>,
    selectedIds: Set<Long>,
    onPick: () -> Unit,
    onUnpick: (Set<Long>) -> Unit,
) {
    val c = PherryTheme.colors
    val picked = remember(ids, selectedIds) { ids.isNotEmpty() && selectedIds.containsAll(ids) }
    FilterChip(
        selected = picked,
        onClick = { if (picked) onUnpick(ids) else onPick() },
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = {
            PhIcon(
                icon = if (picked) Ph.CheckBold else icon,
                contentDescription = null,
                tint = if (picked) c.onEnvelope else c.ink,
                size = 18.dp,
            )
        },
        shape = PherryShape.button,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = c.ink,
            selectedContainerColor = c.envelope,
            selectedLabelColor = c.onEnvelope,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = picked,
            borderColor = c.ink,
            selectedBorderColor = c.envelope,
            borderWidth = 1.5.dp,
            selectedBorderWidth = 1.5.dp,
        ),
    )
}

// ── Albums ───────────────────────────────────────────────────────────────────

/**
 * One album as one strip of film: its name and backup state in edge print, then its newest frames.
 * Frames in the job carry their grease-pencil ring, as on the album's contact sheet.
 */
@Composable
private fun AlbumStrip(
    album: AlbumSummary,
    columns: Int,
    selectedIds: Set<Long>,
    pickOrder: Map<Long, Int>,
    onClick: () -> Unit,
) {
    val c = PherryTheme.colors
    val frames = album.preview.take(columns)
    val picked = remember(album.ids, selectedIds) {
        // Walk the smaller set: a big pick ("Everything") meets many small albums.
        if (selectedIds.size < album.ids.size) selectedIds.count { it in album.ids } else album.ids.count { it in selectedIds }
    }
    val description = buildString {
        append(album.name)
        append(", ")
        append(Fmt.plural(album.itemCount, "item"))
        append(", ")
        append(if (album.unsentCount > 0) "${Fmt.count(album.unsentCount)} not backed up" else "all backed up")
        if (picked > 0) append(", ${Fmt.count(picked)} selected")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(PherryShape.print)
            .background(c.film)
            .clickable(role = Role.Button, onClickLabel = "Open album", onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Column(Modifier.clearAndSetSemantics { }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.sm, end = Spacing.xs, top = Spacing.md, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    EdgeText(album.name, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(Spacing.sm))
                    EdgeText(Fmt.count(album.itemCount), dim = true)
                }
                Spacer(Modifier.width(Spacing.sm))
                if (picked > 0) {
                    StatusTag("${Fmt.count(picked)} selected", TagKind.Job)
                } else if (album.unsentCount > 0) {
                    StatusTag("${Fmt.count(album.unsentCount)} new", TagKind.Job)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PhIcon(Ph.CheckBold, contentDescription = null, tint = c.edge, size = 10.dp)
                        Spacer(Modifier.width(3.dp))
                        EdgeText("All backed up", dim = true)
                    }
                }
                PhIcon(
                    Ph.CaretRight,
                    contentDescription = null,
                    tint = c.filmInk,
                    size = 16.dp,
                    modifier = Modifier.padding(start = Spacing.xs),
                )
            }
            FilmRow(
                count = frames.size,
                columns = columns,
                sprockets = true,
                edgeTop = { i -> FrameNumber(number = i + 1) },
                edgeBottom = { i -> if (frames[i].backedUp) SavedMark() },
            ) { i ->
                val frame = frames[i]
                Frame(
                    model = rememberFrameModel(frame.uri, frame.isVideo),
                    contentDescription = null,
                    aspectRatio = StripAspect,
                    isVideo = frame.isVideo,
                    selectedNumber = pickOrder[frame.id],
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Unexposed strips while the library is read: film, no spinners. */
@Composable
private fun LoadingStrips(columns: Int) {
    val c = PherryTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Reading your library" },
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        repeat(3) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(PherryShape.print)
                    .background(c.film),
            ) {
                Spacer(Modifier.height(Spacing.xxl))
                FilmRow(count = columns, columns = columns, sprockets = true) {
                    BlankFrame(aspectRatio = StripAspect)
                }
            }
        }
    }
}
