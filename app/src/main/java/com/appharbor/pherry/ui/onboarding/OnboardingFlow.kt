package com.appharbor.pherry.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.ui.components.Envelope
import com.appharbor.pherry.ui.components.FilmRow
import com.appharbor.pherry.ui.components.FrameNumber
import com.appharbor.pherry.ui.components.Lamp
import com.appharbor.pherry.ui.components.LampState
import com.appharbor.pherry.ui.components.Notice
import com.appharbor.pherry.ui.components.Ph
import com.appharbor.pherry.ui.components.PhIcon
import com.appharbor.pherry.ui.components.PherryMark
import com.appharbor.pherry.ui.components.PrintButton
import com.appharbor.pherry.ui.components.PrintButtonStyle
import com.appharbor.pherry.ui.connect.ConnectViewModel
import com.appharbor.pherry.ui.connect.ConnectingLine
import com.appharbor.pherry.ui.connect.PairingOptions
import com.appharbor.pherry.ui.permissions.hasMediaPermission
import com.appharbor.pherry.ui.permissions.rememberPermissionAsk
import com.appharbor.pherry.ui.permissions.requiredMediaPermissions
import com.appharbor.pherry.ui.theme.PherryShape
import com.appharbor.pherry.ui.theme.PherryTheme
import com.appharbor.pherry.ui.theme.Spacing

private val StepNames = listOf("Welcome", "Pair", "Photos")

/** What TalkBack announces when a step appears: the step's own title, so focus follows the change. */
private val StepPaneTitles = listOf(
    "Back up your phone to your own computer",
    "Pair your computer",
    "Let Pherry see your photos",
)

private const val StepWelcome = 0
private const val StepPair = 1
private const val StepPhotos = 2

/**
 * First run, full screen: welcome, pair a computer, allow photo access. System Back walks back a
 * step; on the first step it leaves the app as usual.
 */
@Composable
fun OnboardingFlow(
    onFinish: () -> Unit,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val c = PherryTheme.colors
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(StepWelcome) }

    // Photo access already granted: there is nothing to ask, so pairing is the last step.
    fun leavePairing() {
        if (hasMediaPermission(context)) onFinish() else step = StepPhotos
    }

    BackHandler(enabled = step > StepWelcome) { step -= 1 }

    Surface(modifier = Modifier.fillMaxSize(), color = c.paper, contentColor = c.ink) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OnboardingTopBar(
                step = step,
                onBack = { step -= 1 },
                onSkip = if (step == StepPair) ({ leavePairing() }) else null,
            )
            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                modifier = Modifier.weight(1f),
                label = "onboarding-step",
            ) { s ->
                // Each step is its own pane: TalkBack announces the new one when the step changes.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics {
                            paneTitle = "Step ${s + 1} of ${StepNames.size}, ${StepPaneTitles[s]}"
                        },
                ) {
                    when (s) {
                        StepWelcome -> WelcomeStep(onStart = { step = StepPair })
                        StepPair -> PairStep(viewModel = viewModel, onContinue = { leavePairing() })
                        else -> PhotosStep(onDone = onFinish)
                    }
                }
            }
        }
    }
}

// ── Chrome ───────────────────────────────────────────────────────────────────

@Composable
private fun OnboardingTopBar(step: Int, onBack: () -> Unit, onSkip: (() -> Unit)?) {
    val c = PherryTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (step > StepWelcome) {
            IconButton(onClick = onBack) {
                PhIcon(Ph.ArrowLeft, contentDescription = "Back", tint = c.ink)
            }
            Spacer(Modifier.width(Spacing.xs))
        } else {
            Spacer(Modifier.width(Spacing.xl - Spacing.xs))
        }
        StepStrip(current = step)
        Spacer(Modifier.weight(1f))
        if (onSkip != null) {
            PrintButton("Skip for now", onClick = onSkip, style = PrintButtonStyle.Quiet)
        }
    }
}

/**
 * The step indicator: a short strip of film with three tiny frames numbered in edge print. The
 * current frame prints in edge orange; frames already passed are exposed.
 */
@Composable
private fun StepStrip(current: Int) {
    val c = PherryTheme.colors
    Row(
        modifier = Modifier
            .clip(PherryShape.frame)
            .background(c.film)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .clearAndSetSemantics {
                contentDescription = "Step ${current + 1} of ${StepNames.size}, ${StepNames[current]}"
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StepNames.indices.forEach { i ->
            val ink = when {
                i == current -> c.edge
                i < current -> c.filmInk.copy(alpha = 0.75f)
                else -> c.filmInk.copy(alpha = 0.4f)
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = PherryTheme.text.edge, color = ink)
                    PhIcon(Ph.CaretRightBold, contentDescription = null, tint = ink, size = 8.dp)
                }
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .size(width = 22.dp, height = 15.dp)
                        .clip(PherryShape.frame)
                        .background(if (i < current) c.filmRule else c.film2)
                        .then(if (i == current) Modifier.border(1.5.dp, c.edge, PherryShape.frame) else Modifier),
                )
            }
        }
    }
}

/** A step's body scrolls; its actions stay at the bottom, above the keyboard and the nav bar. */
@Composable
private fun StepLayout(
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.lg, bottom = Spacing.xl),
            content = content,
        )
        if (actions != null) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                content = actions,
            )
        }
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineLarge,
        color = PherryTheme.colors.ink,
        modifier = Modifier.semantics { heading() },
    )
}

// ── 1 · Welcome ──────────────────────────────────────────────────────────────

@Composable
private fun WelcomeStep(onStart: () -> Unit) {
    val c = PherryTheme.colors
    StepLayout(
        actions = {
            PrintButton("Get started", onClick = onStart, icon = Ph.ArrowRight, modifier = Modifier.fillMaxWidth())
        },
    ) {
        PherryMark(size = 96.dp)
        Spacer(Modifier.height(Spacing.xl))
        StepTitle("Back up your phone to your own computer")
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = "Pherry sends your photos and videos straight to Pherry Desktop over your Wi-Fi. No account, no cloud.",
            style = MaterialTheme.typography.bodyLarge,
            color = c.ink2,
        )
        Spacer(Modifier.height(Spacing.xl))
        // Unexposed film, waiting for the first backup. Decorative.
        FilmRow(
            count = 4,
            columns = 4,
            sprockets = true,
            modifier = Modifier.clearAndSetSemantics { },
            edgeTop = { i -> FrameNumber(number = i + 1) },
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(PherryShape.frame)
                    .background(c.film2),
            )
        }
        Spacer(Modifier.height(Spacing.xl))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            Fact(Ph.Desktop, "Straight to your computer", "Files never leave your network.")
            Fact(Ph.ShieldCheck, "Checked on arrival", "Every file is verified before it's saved.")
            Fact(Ph.Copy, "Only what's new", "Anything already on your computer is skipped.")
        }
    }
}

@Composable
private fun Fact(@DrawableRes icon: Int, title: String, body: String) {
    val c = PherryTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { },
    ) {
        PhIcon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(Spacing.lg))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = c.ink2)
        }
    }
}

// ── 2 · Pair ─────────────────────────────────────────────────────────────────

@Composable
private fun PairStep(viewModel: ConnectViewModel, onContinue: () -> Unit) {
    val c = PherryTheme.colors
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val serverName by viewModel.serverName.collectAsStateWithLifecycle()
    val endpoint by viewModel.connectedEndpoint.collectAsStateWithLifecycle()

    StepLayout {
        StepTitle("Pair your computer")
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = "On your computer, open Pherry Desktop. It shows a pairing ticket with a QR code.",
            style = MaterialTheme.typography.bodyLarge,
            color = c.ink2,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "Don't have it yet? Install Pherry Desktop on your computer first.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.ink3,
        )
        Spacer(Modifier.height(Spacing.xl))

        if (connectionState == ConnectionState.CONNECTED) {
            PairedEnvelope(
                name = serverName.ifBlank { "your computer" },
                endpoint = endpoint,
                onContinue = onContinue,
            )
            Spacer(Modifier.height(Spacing.sm))
            PrintButton(
                text = "Pair a different computer",
                onClick = viewModel::onDisconnect,
                style = PrintButtonStyle.Quiet,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            if (connectionState == ConnectionState.CONNECTING) {
                ConnectingLine(target = serverName.ifBlank { endpoint.ifBlank { "your computer" } })
                Spacer(Modifier.height(Spacing.lg))
            }
            PairingOptions(viewModel = viewModel)
        }
    }
}

/** The job is done: the envelope comes back stamped with the computer's name. */
@Composable
private fun PairedEnvelope(name: String, endpoint: String, onContinue: () -> Unit) {
    val c = PherryTheme.colors
    val seal = 34.dp
    Envelope {
        Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhIcon(Ph.SealCheck, contentDescription = null, tint = c.onEnvelope, size = seal)
                Spacer(Modifier.width(Spacing.md))
                Text(
                    text = "Paired with $name",
                    style = MaterialTheme.typography.headlineMedium,
                    color = c.onEnvelope,
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            // The data line sits under the title, lined up with its text.
            Row(Modifier.padding(start = seal + Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Lamp(LampState.On, onEnvelope = true)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = endpoint.ifBlank { "Connected" }.uppercase(),
                    style = PherryTheme.text.monoCaps,
                    color = c.onEnvelope2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(Spacing.lg))
        PrintButton(
            text = "Continue",
            onClick = onContinue,
            icon = Ph.ArrowRight,
            onEnvelope = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── 3 · Photos ───────────────────────────────────────────────────────────────

@Composable
private fun PhotosStep(onDone: () -> Unit) {
    val c = PherryTheme.colors
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnDone by rememberUpdatedState(onDone)
    var finished by remember { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var blocked by rememberSaveable { mutableStateOf(false) }

    // Both the permission result and the next resume can report a grant; finish only once.
    fun finish() {
        if (finished) return
        finished = true
        currentOnDone()
    }

    val mediaAsk = rememberPermissionAsk(*requiredMediaPermissions())
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasMediaPermission(context)) {
            finish()
        } else {
            denied = true
            // Only a real "don't ask again" swaps Allow for app settings; a dismissed dialog asks again.
            blocked = mediaAsk.blockedAfterDenial()
        }
    }

    // Already granted (or granted from system settings and come back): skip this step.
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && hasMediaPermission(context)) finish()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    StepLayout(
        actions = {
            if (blocked) {
                PrintButton(
                    text = "Open app settings",
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        )
                    },
                    icon = Ph.Gear,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                PrintButton(
                    text = "Allow access",
                    onClick = {
                        mediaAsk.beforeLaunch()
                        permissionLauncher.launch(requiredMediaPermissions())
                    },
                    icon = Ph.Image,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PrintButton("Not now", onClick = { finish() }, style = PrintButtonStyle.Quiet, modifier = Modifier.fillMaxWidth())
        },
    ) {
        StepTitle("Let Pherry see your photos")
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = "Pherry needs access to back up your photos and videos. They only go to your computer.",
            style = MaterialTheme.typography.bodyLarge,
            color = c.ink2,
        )
        Spacer(Modifier.height(Spacing.xl))
        // The library as Pherry sees it before access: blank frames. Decorative.
        FilmRow(
            count = 4,
            columns = 4,
            sprockets = true,
            modifier = Modifier.clearAndSetSemantics { },
        ) { i ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(PherryShape.frame)
                    .background(c.film2),
                contentAlignment = Alignment.Center,
            ) {
                PhIcon(
                    icon = if (i == 2) Ph.Video else Ph.Image,
                    contentDescription = null,
                    tint = c.filmInk.copy(alpha = 0.45f),
                    size = 20.dp,
                )
            }
        }
        if (denied) {
            Spacer(Modifier.height(Spacing.xl))
            Notice(
                title = if (blocked) "Photo access is off" else "Pherry can't see your photos yet",
                detail = if (blocked) {
                    "Android won't ask again. Open Pherry's settings, choose Permissions, then Photos and videos, and allow access."
                } else {
                    "Allow access to back them up. You can also do it later from Home."
                },
                error = false,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
