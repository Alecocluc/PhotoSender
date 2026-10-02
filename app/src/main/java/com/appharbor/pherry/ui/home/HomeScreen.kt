package com.appharbor.pherry.ui.home

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appharbor.pherry.data.db.UploadRecord
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.data.network.RememberedComputer
import com.appharbor.pherry.data.upload.TransferState
import com.appharbor.pherry.ui.components.DateStamp
import com.appharbor.pherry.ui.components.Envelope
import com.appharbor.pherry.ui.components.EnvelopeCheck
import com.appharbor.pherry.ui.components.EnvelopeField
import com.appharbor.pherry.ui.components.Fmt
import com.appharbor.pherry.ui.components.Frame
import com.appharbor.pherry.ui.components.FrameNumber
import com.appharbor.pherry.ui.components.JobBar
import com.appharbor.pherry.ui.components.Lamp
import com.appharbor.pherry.ui.components.LampState
import com.appharbor.pherry.ui.components.LocalSnackbarHost
import com.appharbor.pherry.ui.components.Notice
import com.appharbor.pherry.ui.components.Perforation
import com.appharbor.pherry.ui.components.Ph
import com.appharbor.pherry.ui.components.PhIcon
import com.appharbor.pherry.ui.components.PrintButton
import com.appharbor.pherry.ui.components.PrintButtonStyle
import com.appharbor.pherry.ui.components.ScrollingStrip
import com.appharbor.pherry.ui.components.SectionHeading
import com.appharbor.pherry.ui.components.SyncConfirmDialog
import com.appharbor.pherry.ui.gallery.rememberFrameModel
import com.appharbor.pherry.ui.permissions.LocalNetworkAccess
import com.appharbor.pherry.ui.permissions.hasMediaPermission
import com.appharbor.pherry.ui.permissions.rememberPermissionAsk
import com.appharbor.pherry.ui.permissions.rememberLocalNetworkAccess
import com.appharbor.pherry.ui.permissions.requiredMediaPermissions
import com.appharbor.pherry.ui.settings.SettingsViewModel
import com.appharbor.pherry.ui.theme.PherryShape
import com.appharbor.pherry.ui.theme.PherryTheme
import com.appharbor.pherry.ui.theme.Spacing
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onConnectClick: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenTransfers: () -> Unit,
    onOpenSettings: () -> Unit,
    onBeforeTransfer: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
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
    val confirmDestructiveSync by viewModel.confirmDestructiveSync.collectAsStateWithLifecycle()
    val pendingSyncPlan by viewModel.pendingSyncPlan.collectAsStateWithLifecycle()
    val isPreparingSync by viewModel.isPreparingSync.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val recentSent by viewModel.recentSent.collectAsStateWithLifecycle()
    val remembered by viewModel.rememberedComputer.collectAsStateWithLifecycle()
    val canDelete by viewModel.canDelete.collectAsStateWithLifecycle()
    val isQueueing by viewModel.isQueueing.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    var hasPermission by remember { mutableStateOf(hasMediaPermission(context)) }
    var permissionBlocked by rememberSaveable { mutableStateOf(false) }
    var askAutoBackup by remember { mutableStateOf(false) }
    val network = rememberLocalNetworkAccess(onGranted = { viewModel.reconnect() })
    val mediaAsk = rememberPermissionAsk(*requiredMediaPermissions())

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermission = results.values.all { it }
        // Only a real "don't ask again" swaps Allow for app settings; a dismissed dialog asks again.
        permissionBlocked = !hasPermission && mediaAsk.blockedAfterDenial()
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = hasMediaPermission(context)
                if (hasPermission) permissionBlocked = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val connected = connectionState == ConnectionState.CONNECTED
    // serverName clears when the link drops; the saved name still says which computer this is.
    val computer = serverName.ifBlank { remembered?.name.orEmpty() }.ifBlank { "your computer" }

    // "Back up N now" reports back once the files are queued; open Transfers from here so the
    // NavController never runs inside the ViewModel's scope.
    LaunchedEffect(viewModel) {
        viewModel.queued.collect { onOpenTransfers() }
    }

    // Refresh the unsent count on open, on connect, on permission grant, and when a transfer ends.
    // The ViewModel's staleness guard keeps this from re-scanning a large library on every recomposition.
    LaunchedEffect(connectionState, hasPermission, transferState.isTransferring) {
        if (connected && hasPermission && !transferState.isTransferring) viewModel.refreshUnsent()
    }

    // The desktop-deletion half of a sync reports back here; force a recount so the envelope matches.
    // Clearing the summary changes this effect's key and cancels it, so the snackbar is shown from
    // the screen's scope; otherwise it would be dismissed the frame after it appears.
    LaunchedEffect(syncState.summary) {
        val summary = syncState.summary ?: return@LaunchedEffect
        viewModel.clearSyncSummary()
        if (connected && hasPermission) viewModel.refreshUnsent(force = true)
        scope.launch { snackbar.showSnackbar(summary) }
    }

    pendingSyncPlan?.let { plan ->
        SyncConfirmDialog(
            plan = plan,
            computerName = computer,
            confirmDestructive = confirmDestructiveSync,
            canDelete = canDelete,
            onScanTicket = {
                viewModel.cancelSync()
                onConnectClick()
            },
            onConfirm = {
                if (!plan.isNoOp) onBeforeTransfer()
                val hasUploads = viewModel.confirmSync()
                if (hasUploads) onOpenTransfers()
            },
            onDismiss = { viewModel.cancelSync() },
        )
    }

    if (askAutoBackup) {
        AlertDialog(
            onDismissRequest = { askAutoBackup = false },
            containerColor = PherryTheme.colors.sheet,
            title = { Text("Turn on auto-backup") },
            text = {
                Text(
                    "Pherry will send new photos and videos to $computer by itself, about every 15 minutes " +
                        "when the phone is on Wi-Fi. Start with everything already on this phone, or only what you take from now on?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsViewModel.enableAutoBackup(includeExisting = true)
                        askAutoBackup = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = PherryTheme.colors.ink),
                ) { Text("Everything") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        settingsViewModel.enableAutoBackup(includeExisting = false)
                        askAutoBackup = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = PherryTheme.colors.ink),
                ) { Text("Only new") }
            },
        )
    }

    val state = envelopeState(
        networkGranted = network.granted,
        connectionState = connectionState,
        remembered = remembered,
        hasPermission = hasPermission,
        transfer = transferState,
        queuedCount = queuedCount,
        unsent = unsent,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.sm, bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        item(key = "envelope") {
            HomeEnvelope(
                state = state,
                network = network,
                permissionBlocked = permissionBlocked,
                computer = computer,
                remembered = remembered,
                canDelete = canDelete,
                isQueueing = isQueueing,
                completedCount = completedCount,
                queuedCount = queuedCount,
                unsent = unsent,
                transfer = transferState,
                lastBackupAt = lastBackupAt,
                isPreparingSync = isPreparingSync,
                autoBackupEnabled = autoBackupEnabled,
                wifiOnly = wifiOnly,
                requiresCharging = autoBackupRequiresCharging,
                onConnect = onConnectClick,
                onRetryConnect = viewModel::reconnect,
                onAllowAccess = {
                    mediaAsk.beforeLaunch()
                    permissionLauncher.launch(requiredMediaPermissions())
                },
                onOpenAppSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                },
                onBackUp = {
                    onBeforeTransfer()
                    viewModel.backUpNew()
                },
                onSync = { viewModel.prepareSync() },
                onRefresh = { viewModel.refreshUnsent(force = true) },
                onViewTransfer = onOpenTransfers,
                onStop = {
                    viewModel.stopTransfer()
                    scope.launch { snackbar.showSnackbar("Stopped. The rest stay queued until you resume.") }
                },
                onResume = {
                    onBeforeTransfer()
                    viewModel.resumeQueued()
                    onOpenTransfers()
                },
                onAutoBackupChange = { on -> if (on) askAutoBackup = true else settingsViewModel.disableAutoBackup() },
                onWifiOnlyChange = settingsViewModel::onWifiOnlyTransferChanged,
                onChargingChange = settingsViewModel::onAutoBackupChargingChanged,
            )
        }

        // First steps only for a phone that has never paired; a saved computer that isn't
        // answering gets its own envelope instead.
        if ((state == HomeState.NoComputer || state == HomeState.NoNetwork) && remembered == null) {
            item(key = "how") { HowItWorks() }
        }

        if (failedCount > 0 && !transferState.isTransferring) {
            item(key = "failed") {
                // A dropped link never fails a file (it goes back to the queue): FAILED means the
                // computer answered with an error, or this phone couldn't read the file.
                Notice(
                    title = "${Fmt.plural(failedCount, "file")} didn't reach $computer",
                    detail = "${computer.replaceFirstChar { it.uppercase() }} couldn't save them, or this phone couldn't read them. Nothing was lost on this phone.",
                    actionLabel = "Retry ${Fmt.count(failedCount)}",
                    onAction = {
                        onBeforeTransfer()
                        viewModel.retryFailed()
                        onOpenTransfers()
                    },
                )
            }
        }

        if (recentSent.isNotEmpty()) {
            item(key = "recent-head") {
                SectionHeading(
                    text = "Recently sent",
                    trailing = {
                        TextButton(onClick = onOpenTransfers) {
                            Text("History", style = MaterialTheme.typography.labelLarge, color = PherryTheme.colors.ink)
                        }
                    },
                )
            }
            item(key = "recent-strip") {
                RecentStrip(records = recentSent, total = completedCount)
            }
        }

        if (state == HomeState.Idle || state == HomeState.Backlog) {
            item(key = "library") {
                TextButton(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth()) {
                    PhIcon(Ph.Images, contentDescription = null, size = 18.dp, tint = PherryTheme.colors.ink)
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Pick photos to send from the Library", style = MaterialTheme.typography.labelLarge, color = PherryTheme.colors.ink)
                }
            }
        }
    }
}

// ── Envelope ─────────────────────────────────────────────────────────────────

/** [Unreachable]: a saved computer that isn't answering (or the user disconnected); still paired. */
enum class HomeState { NoNetwork, NoComputer, Unreachable, Connecting, NoAccess, Counting, Sending, Paused, Backlog, Idle }

private fun envelopeState(
    networkGranted: Boolean,
    connectionState: ConnectionState,
    remembered: RememberedComputer?,
    hasPermission: Boolean,
    transfer: TransferState,
    queuedCount: Int,
    unsent: UnsentState,
): HomeState = when {
    transfer.isTransferring -> HomeState.Sending
    !networkGranted -> HomeState.NoNetwork
    connectionState == ConnectionState.CONNECTING -> HomeState.Connecting
    connectionState != ConnectionState.CONNECTED && remembered != null -> HomeState.Unreachable
    connectionState != ConnectionState.CONNECTED -> HomeState.NoComputer
    !hasPermission -> HomeState.NoAccess
    queuedCount > 0 -> HomeState.Paused
    unsent.isLoading && !unsent.computed -> HomeState.Counting
    unsent.count > 0 || unsent.deleteCount > 0 -> HomeState.Backlog
    unsent.computed -> HomeState.Idle
    else -> HomeState.Counting
}

@Composable
private fun HomeEnvelope(
    state: HomeState,
    network: LocalNetworkAccess,
    permissionBlocked: Boolean,
    computer: String,
    remembered: RememberedComputer?,
    canDelete: Boolean,
    isQueueing: Boolean,
    completedCount: Int,
    queuedCount: Int,
    unsent: UnsentState,
    transfer: TransferState,
    lastBackupAt: Long,
    isPreparingSync: Boolean,
    autoBackupEnabled: Boolean,
    wifiOnly: Boolean,
    requiresCharging: Boolean,
    onConnect: () -> Unit,
    onRetryConnect: () -> Unit,
    onAllowAccess: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onBackUp: () -> Unit,
    onSync: () -> Unit,
    onRefresh: () -> Unit,
    onViewTransfer: () -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onAutoBackupChange: (Boolean) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onChargingChange: (Boolean) -> Unit,
) {
    val c = PherryTheme.colors
    val byUser = remembered?.disconnectedByUser == true
    // One short sentence per state for TalkBack. It lives on its own leaf above the content, a node that
    // stays put across states, so each change is announced once; the progress numbers below never are.
    val announcement = when (state) {
        HomeState.NoNetwork -> if (network.blocked) "Wi-Fi access is off" else "Pherry needs Wi-Fi access"
        HomeState.NoComputer -> "Pair a computer to start"
        HomeState.Unreachable -> if (byUser) "Disconnected from $computer" else "Can't reach $computer"
        HomeState.Connecting -> "Connecting to $computer"
        HomeState.NoAccess -> if (permissionBlocked) "Photo access is off" else "Allow photo access"
        HomeState.Counting -> "Checking your library"
        HomeState.Sending -> "Sending to $computer"
        HomeState.Paused -> "Sending paused"
        HomeState.Backlog -> if (unsent.count > 0) {
            "${Fmt.count(unsent.count)} new to back up to $computer"
        } else {
            "${Fmt.count(unsent.deleteCount)} to sync with $computer"
        }
        HomeState.Idle -> "Everything is on $computer"
    }
    Envelope {
        // The announcement's leaf: no caption, nothing drawn. A thin full-width strip that never moves or
        // resizes; the swapped-out content below can't carry it, and a container would re-announce on
        // every relayout of the numbers inside it.
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .semantics {
                    contentDescription = announcement
                    liveRegion = LiveRegionMode.Polite
                },
        )

        Box(Modifier.fillMaxWidth()) {
            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                label = "envelope",
            ) { s ->
                val link = linkLamp(s)
                Column(Modifier.fillMaxWidth()) {
                    when (s) {
                        HomeState.NoNetwork -> if (network.blocked) {
                            EnvelopeMessage(
                                title = "Wi-Fi access is off",
                                body = "Android won't ask again. Open Pherry's settings, choose Permissions, then Nearby devices, and allow it.",
                                lamp = link,
                            ) {
                                PrintButton("Open app settings", onClick = network::openSettings, icon = Ph.Gear, onEnvelope = true, modifier = Modifier.fillMaxWidth())
                            }
                        } else {
                            EnvelopeMessage(
                                title = "Let Pherry use your Wi-Fi",
                                body = "Android asks before an app talks to other devices on your Wi-Fi. Pherry needs it to reach Pherry Desktop, and talks to nothing else.",
                                lamp = link,
                            ) {
                                PrintButton("Allow", onClick = network::request, icon = Ph.Wifi, onEnvelope = true, modifier = Modifier.fillMaxWidth())
                            }
                        }

                        HomeState.NoComputer -> EnvelopeMessage(
                            title = "Pair a computer to start",
                            body = "Pherry sends your photos and videos to Pherry Desktop on your own computer, over your Wi-Fi. No account, no cloud.",
                            lamp = link,
                        ) {
                            PrintButton("Pair a computer", onClick = onConnect, icon = Ph.Desktop, onEnvelope = true, modifier = Modifier.fillMaxWidth())
                        }

                        HomeState.Unreachable -> EnvelopeMessage(
                            title = if (byUser) "Disconnected from $computer" else "Can't reach $computer",
                            body = if (byUser) {
                                "Pherry reconnects the next time it opens. Connect now to keep backing up."
                            } else {
                                "Make sure Pherry Desktop is open on it and both are on the same Wi-Fi."
                            },
                            lamp = link,
                        ) {
                            PrintButton(
                                if (byUser) "Connect" else "Try again",
                                onClick = onRetryConnect,
                                icon = Ph.Refresh,
                                onEnvelope = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            PrintButton("Pair another computer", onClick = onConnect, style = PrintButtonStyle.Outline, onEnvelope = true, modifier = Modifier.fillMaxWidth())
                        }

                        HomeState.Connecting -> EnvelopeMessage(
                            title = "Connecting…",
                            body = "Make sure Pherry Desktop is open on ${computer} and both are on the same Wi-Fi.",
                            lamp = link,
                        ) {
                            PrintButton("Choose another computer", onClick = onConnect, style = PrintButtonStyle.Outline, onEnvelope = true, modifier = Modifier.fillMaxWidth())
                        }

                        HomeState.NoAccess -> if (permissionBlocked) {
                            EnvelopeMessage(
                                title = "Photo access is off",
                                body = "Android won't ask again. Open Pherry's settings, choose Permissions, then Photos and videos, and allow access.",
                            ) {
                                PrintButton("Open app settings", onClick = onOpenAppSettings, icon = Ph.Gear, onEnvelope = true, modifier = Modifier.fillMaxWidth())
                            }
                        } else {
                            EnvelopeMessage(
                                title = "Allow photo access",
                                body = "Pherry reads the photos and videos on this phone so it can back them up. They only travel to $computer.",
                            ) {
                                PrintButton("Allow access", onClick = onAllowAccess, icon = Ph.Image, onEnvelope = true, modifier = Modifier.fillMaxWidth())
                            }
                        }

                        HomeState.Counting -> {
                            Fields(library = null, backedUp = backedUp(unsent, completedCount), computer = computer, link = link)
                            Spacer(Modifier.height(Spacing.lg))
                            // The link is live (TO stays green); this lamp blinks for the scan.
                            EnvelopeMessage(title = "Checking your library…", body = "Looking for photos that aren't on $computer yet.", lamp = LampState.Busy)
                        }

                        HomeState.Sending -> SendingBody(transfer = transfer, computer = computer, onView = onViewTransfer, onStop = onStop)

                        HomeState.Paused -> {
                            Fields(
                                library = unsent.libraryCount.takeIf { unsent.computed },
                                backedUp = backedUp(unsent, completedCount, pending = queuedCount),
                                computer = computer,
                                link = link,
                            )
                            BigCount(value = queuedCount, caption = "${if (queuedCount == 1) "file is" else "files are"} queued, waiting to send")
                            Spacer(Modifier.height(Spacing.lg))
                            PrintButton("Resume sending", onClick = onResume, icon = Ph.Play, onEnvelope = true, modifier = Modifier.fillMaxWidth())
                        }

                        HomeState.Backlog -> {
                            Fields(library = unsent.libraryCount, backedUp = backedUp(unsent, completedCount), computer = computer, link = link)
                            if (unsent.count > 0) {
                                BigCount(
                                    value = unsent.count,
                                    caption = "new ${if (unsent.count == 1) "photo or video" else "photos and videos"} · ${Fmt.bytes(unsent.bytes)}",
                                )
                            }
                            if (unsent.syncMode && unsent.deleteCount > 0) {
                                Spacer(Modifier.height(Spacing.md))
                                DeleteLine(count = unsent.deleteCount, computer = computer, canDelete = canDelete)
                            }
                            Spacer(Modifier.height(Spacing.lg))
                            if (unsent.syncMode) {
                                PrintButton(
                                    text = if (isPreparingSync) "Checking…" else "Sync now",
                                    onClick = onSync,
                                    enabled = !isPreparingSync,
                                    icon = Ph.Refresh,
                                    onEnvelope = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                PrintButton(
                                    text = if (isQueueing) "Preparing…" else "Back up ${Fmt.count(unsent.count)} now",
                                    onClick = onBackUp,
                                    enabled = !isQueueing,
                                    icon = Ph.Upload,
                                    onEnvelope = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        HomeState.Idle -> {
                            Fields(library = unsent.libraryCount, backedUp = backedUp(unsent, completedCount), computer = computer, link = link)
                            Spacer(Modifier.height(Spacing.lg))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PhIcon(Ph.SealCheck, contentDescription = null, tint = c.onEnvelope, size = 34.dp)
                                Spacer(Modifier.width(Spacing.md))
                                Text(
                                    "Everything is on $computer",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = c.onEnvelope,
                                )
                            }
                        }
                    }
                }
            }

            // Check again by hand: top end, level with the form's first row. Kept outside the swapped
            // content so TalkBack focus stays on it when Idle and Backlog trade places.
            if (state == HomeState.Idle || state == HomeState.Backlog) {
                IconButton(onClick = onRefresh, modifier = Modifier.align(Alignment.TopEnd).size(RefreshButtonSize)) {
                    PhIcon(Ph.Refresh, contentDescription = "Check for new photos", tint = c.onEnvelope, size = 20.dp)
                }
            }
        }

        // The order form: how Pherry works by itself. Only once there's a computer to send to.
        if (state !in setOf(HomeState.NoNetwork, HomeState.NoComputer, HomeState.Unreachable, HomeState.Connecting, HomeState.NoAccess)) {
            Spacer(Modifier.height(Spacing.lg))
            Perforation(color = c.onEnvelope.copy(alpha = 0.3f))
            Spacer(Modifier.height(Spacing.xs))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.Center,
            ) {
                EnvelopeCheck("Auto-backup", checked = autoBackupEnabled, onCheckedChange = onAutoBackupChange)
                EnvelopeCheck("Wi-Fi only", checked = wifiOnly, onCheckedChange = onWifiOnlyChange)
                EnvelopeCheck(
                    "While charging",
                    checked = requiresCharging,
                    onCheckedChange = onChargingChange,
                    enabled = autoBackupEnabled,
                    disabledReason = "Turn on auto-backup to use this",
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DateStamp(if (lastBackupAt > 0) "Last backup ${Fmt.stamp(lastBackupAt)}" else "No backup yet")
            }
        }
    }
}

/**
 * The envelope's BACKED UP figure, measured against this phone: once a scan has run it is "on this
 * phone minus not yet sent", so it can never read more than ON THIS PHONE (the ledger also counts files
 * since deleted here). Before the first scan, the ledger's count is the best there is. [pending] is the
 * queue: "Back up N now" zeroes the unsent count as it queues, so queued files must not read as sent.
 */
private fun backedUp(unsent: UnsentState, completedCount: Int, pending: Int = 0): Int =
    if (unsent.computed) {
        (unsent.libraryCount - maxOf(unsent.count, pending)).coerceAtLeast(0)
    } else {
        completedCount
    }

private val RefreshButtonSize = 40.dp

/** The computer's lamp: blinking while connecting or sending, hollow with no link, green on a live one. */
private fun linkLamp(state: HomeState): LampState = when (state) {
    HomeState.Sending, HomeState.Connecting -> LampState.Busy
    HomeState.NoComputer, HomeState.NoNetwork, HomeState.Unreachable -> LampState.Idle
    else -> LampState.On
}

/**
 * The envelope's printed form, first thing on it: what is on this phone, what is backed up, and the
 * computer it goes TO, with its lamp. A long name wraps TO onto a line of its own rather than squeezing
 * the counts.
 */
@Composable
private fun Fields(library: Int?, backedUp: Int, computer: String, link: LampState) {
    FlowRow(
        // Room at the end for the refresh button (Idle, Backlog). Kept in every state, so the form
        // doesn't reflow when the count lands.
        modifier = Modifier.fillMaxWidth().padding(end = RefreshButtonSize + Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        EnvelopeField("On this phone", library?.let(Fmt::count) ?: "…")
        EnvelopeField("Backed up", Fmt.count(backedUp))
        EnvelopeField("To", computer, lamp = link)
    }
}

@Composable
private fun BigCount(value: Int, caption: String) {
    val c = PherryTheme.colors
    Column(
        Modifier
            .padding(top = Spacing.lg)
            .semantics(mergeDescendants = true) { contentDescription = "${Fmt.count(value)} $caption" },
    ) {
        Text(Fmt.count(value), style = MaterialTheme.typography.displayLarge, color = c.onEnvelope)
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = c.onEnvelope)
    }
}

@Composable
private fun DeleteLine(count: Int, computer: String, canDelete: Boolean) {
    val c = PherryTheme.colors
    Row(
        Modifier
            .clip(PherryShape.print)
            .background(c.onEnvelope.copy(alpha = 0.08f))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canDelete) {
            Box(Modifier.size(18.dp).clip(PherryShape.frame).background(c.red), contentAlignment = Alignment.Center) {
                PhIcon(Ph.XBold, contentDescription = null, tint = MaterialTheme.colorScheme.onError, size = 11.dp)
            }
        } else {
            // Nothing will be deleted without the ticket, so no red mark.
            PhIcon(Ph.Info, contentDescription = null, tint = c.onEnvelope, size = 18.dp)
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            if (canDelete) {
                "${Fmt.count(count)} no longer on this phone will also be deleted from $computer"
            } else {
                "${Fmt.count(count)} no longer on this phone. To delete them on $computer, scan its pairing ticket once."
            },
            style = MaterialTheme.typography.bodySmall,
            color = c.onEnvelope,
        )
    }
}

/** A titled message on the envelope. A [lamp] prints before the title, centred on its first line. */
@Composable
private fun EnvelopeMessage(
    title: String,
    body: String,
    lamp: LampState? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val c = PherryTheme.colors
    val titleStyle = MaterialTheme.typography.headlineMedium
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            if (lamp != null) {
                // A box one title line tall, so a name that wraps keeps the lamp beside its first line.
                val firstLine = with(LocalDensity.current) { titleStyle.lineHeight.toDp() }
                Box(Modifier.height(firstLine), contentAlignment = Alignment.Center) {
                    Lamp(lamp, onEnvelope = true, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(Spacing.md))
            }
            Text(title, style = titleStyle, color = c.onEnvelope, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.onEnvelope)
        if (actions != null) {
            Spacer(Modifier.height(Spacing.lg))
            actions()
        }
    }
}

@Composable
private fun SendingBody(transfer: TransferState, computer: String, onView: () -> Unit, onStop: () -> Unit) {
    val c = PherryTheme.colors
    val progress by animateFloatAsState(transfer.progressPercent.coerceIn(0f, 1f), tween(300), label = "home-progress")
    val done = transfer.completedFiles
    val total = transfer.totalFiles
    Column(Modifier.fillMaxWidth()) {
        // The same TO field as the form, lamp blinking while the job runs.
        EnvelopeField("To", computer, lamp = LampState.Busy)
        Spacer(Modifier.height(Spacing.lg))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Fmt.count(done), style = MaterialTheme.typography.displayLarge, color = c.onEnvelope)
            Text(
                " / ${if (total > 0) Fmt.count(total) else "…"}",
                style = MaterialTheme.typography.headlineMedium,
                color = c.onEnvelope2,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.headlineMedium,
                color = c.onEnvelope,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Spacer(Modifier.height(Spacing.md))
        JobBar(progress)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = buildList {
                if (transfer.totalBytes > 0) add("${Fmt.bytes(transfer.transferredBytes)} of ${Fmt.bytes(transfer.totalBytes)}")
                if (transfer.currentSpeedBytesPerSec > 0) add(Fmt.speed(transfer.currentSpeedBytesPerSec))
                Fmt.remaining(transfer.estimatedSecondsRemaining).takeIf { it.isNotEmpty() }?.let(::add)
                if (transfer.skippedFiles > 0) add("${Fmt.count(transfer.skippedFiles)} already there")
            }.let(Fmt::line).uppercase(),
            style = PherryTheme.text.monoCaps,
            color = c.onEnvelope2,
        )
        Spacer(Modifier.height(Spacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PrintButton("See transfer", onClick = onView, onEnvelope = true, modifier = Modifier.weight(1f))
            PrintButton("Stop", onClick = onStop, style = PrintButtonStyle.Outline, icon = Ph.Stop, onEnvelope = true)
        }
    }
}

// ── First steps ──────────────────────────────────────────────────────────────

/** Three numbered steps for someone who skipped pairing: what to install, what to scan, what happens. */
@Composable
private fun HowItWorks() {
    val c = PherryTheme.colors
    Column(Modifier.fillMaxWidth()) {
        SectionHeading("How it works")
        listOf(
            "Install Pherry Desktop on your computer and open it.",
            "It shows a pairing ticket with a QR code. Tap Pair a computer here and scan it.",
            "Back up everything once, then only what's new. Files go straight to a folder on your computer.",
        ).forEachIndexed { i, step ->
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.md), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(28.dp).clip(PherryShape.frame).background(c.film),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${i + 1}", style = PherryTheme.text.edge, color = c.edge)
                }
                Spacer(Modifier.width(Spacing.md))
                Text(step, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f).padding(top = 4.dp))
            }
            if (i < 2) com.appharbor.pherry.ui.components.Hairline()
        }
    }
}

// ── Recently sent ────────────────────────────────────────────────────────────

@Composable
private fun RecentStrip(records: List<UploadRecord>, total: Int) {
    ScrollingStrip(
        count = records.size,
        frameWidth = 128.dp,
        edgeTop = { i -> FrameNumber(number = (total - i).coerceAtLeast(1), trailing = Fmt.clock(records[i].uploadedAt)) },
    ) { i ->
        val r = records[i]
        val isVideo = Fmt.isVideoName(r.fileName)
        // Videos need the MediaStore thumbnail: the image loader can't decode a video stream.
        val model = r.contentUri.takeIf { it.isNotBlank() }?.let { rememberFrameModel(Uri.parse(it), isVideo) }
        Frame(
            model = model,
            contentDescription = "${r.fileName}, ${Fmt.bytes(r.fileSize)}, sent ${Fmt.ago(r.uploadedAt)}",
            aspectRatio = 4f / 3f,
            isVideo = isVideo,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
