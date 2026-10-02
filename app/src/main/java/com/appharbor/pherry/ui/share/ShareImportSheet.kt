package com.appharbor.pherry.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.data.share.SharedItem
import com.appharbor.pherry.ui.components.Fmt
import com.appharbor.pherry.ui.components.Frame
import com.appharbor.pherry.ui.components.FrameNumber
import com.appharbor.pherry.ui.components.Notice
import com.appharbor.pherry.ui.components.Ph
import com.appharbor.pherry.ui.components.PrintButton
import com.appharbor.pherry.ui.components.PrintButtonStyle
import com.appharbor.pherry.ui.components.ScrollingStrip
import com.appharbor.pherry.ui.gallery.rememberFrameModel
import com.appharbor.pherry.ui.theme.PherryShape
import com.appharbor.pherry.ui.theme.PherryTheme
import com.appharbor.pherry.ui.theme.Spacing

/**
 * Review sheet shown when media is shared into Pherry from another app: what was shared, where it
 * goes, and one button that hands it to the upload pipeline. Hosted in a ModalBottomSheet by the
 * shell, which supplies the drag handle, System Back and the navigation bar inset.
 */
@Composable
fun ShareImportSheet(
    onConnect: () -> Unit,
    onSent: () -> Unit,
    onBeforeTransfer: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: ShareImportViewModel = hiltViewModel(),
) {
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val pendingUris by viewModel.pendingUris.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val serverName by viewModel.serverName.collectAsStateWithLifecycle()
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()

    val c = PherryTheme.colors
    val connected = connectionState == ConnectionState.CONNECTED
    val computer = serverName.ifBlank { "your computer" }
    val loading = preview.isLoading
    val nothingToSend = !loading && preview.count == 0
    // Anything that isn't a photo or video is dropped by the importer; say so rather than lose it quietly.
    val dropped = if (loading) 0 else (pendingUris.size - preview.count).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xl),
    ) {
        Text(
            text = "Send to $computer",
            style = MaterialTheme.typography.headlineSmall,
            color = c.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = countsLine(preview, pendingCount = pendingUris.size).uppercase(),
            style = PherryTheme.text.monoCaps,
            color = c.ink3,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        Spacer(Modifier.height(Spacing.lg))

        when {
            loading -> PlaceholderStrip(count = pendingUris.size.coerceIn(1, 6))
            preview.count > 0 -> SharedStrip(items = preview.items)
        }

        if (nothingToSend) {
            Notice(
                title = "Nothing here Pherry can send",
                detail = "Pherry sends photos and videos. Share those from your gallery or camera app.",
                error = false,
            )
        } else if (dropped > 0) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "${Fmt.plural(dropped, "other file")} ${if (dropped == 1) "isn't a photo or video, so it won't" else "aren't photos or videos, so they won't"} be sent.",
                style = MaterialTheme.typography.bodySmall,
                color = c.ink2,
            )
        }

        if (!nothingToSend) {
            when (connectionState) {
                ConnectionState.CONNECTED -> Unit
                ConnectionState.CONNECTING -> {
                    Spacer(Modifier.height(Spacing.lg))
                    Notice(
                        title = "Connecting to $computer…",
                        detail = "Make sure Pherry Desktop is open and both are on the same Wi-Fi.",
                        actionLabel = "Change",
                        onAction = onConnect,
                        error = false,
                    )
                }
                else -> {
                    Spacer(Modifier.height(Spacing.lg))
                    Notice(
                        title = "Pair a computer to send these",
                        detail = "Open Pherry Desktop on your computer, then scan its pairing ticket.",
                        actionLabel = "Pair",
                        onAction = onConnect,
                        error = false,
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.xl))

        if (!nothingToSend) {
            PrintButton(
                text = when {
                    isSending -> "Preparing…"
                    preview.count > 0 -> "Send ${Fmt.count(preview.count)}"
                    else -> "Send"
                },
                onClick = {
                    onBeforeTransfer()
                    viewModel.confirmSend(onQueued = onSent)
                },
                icon = Ph.Send,
                enabled = connected && preview.count > 0 && !loading && !isSending,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.xs))
        }
        PrintButton(
            text = if (nothingToSend) "Close" else "Cancel",
            onClick = onDismiss,
            style = if (nothingToSend) PrintButtonStyle.Outline else PrintButtonStyle.Quiet,
            enabled = !isSending,
            modifier = Modifier.fillMaxWidth(),
        )

        if (!nothingToSend) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = "They land in the Shared folder on your computer. Anything already there is skipped.",
                style = MaterialTheme.typography.bodySmall,
                color = c.ink2,
            )
        }
    }
}

/** "3 photos · 1 video · 24 MB"; sizes some providers don't report are left out rather than shown as 0. */
private fun countsLine(preview: SharePreview, pendingCount: Int): String {
    if (preview.isLoading) return "${Fmt.plural(pendingCount, "file")} · reading…"
    if (preview.count == 0) return "No photos or videos"
    val bytes = preview.items.sumOf { it.size.coerceAtLeast(0L) }
    return buildList {
        if (preview.photoCount > 0) add(Fmt.plural(preview.photoCount, "photo"))
        if (preview.videoCount > 0) add(Fmt.plural(preview.videoCount, "video"))
        if (bytes > 0) add(Fmt.bytes(bytes))
    }.joinToString(" · ")
}

@Composable
private fun SharedStrip(items: List<SharedItem>) {
    ScrollingStrip(
        count = items.size,
        frameWidth = 112.dp,
        edgeTop = { i ->
            FrameNumber(
                number = i + 1,
                trailing = items[i].size.takeIf { it > 0 }?.let(Fmt::bytes),
            )
        },
    ) { i ->
        val item = items[i]
        Frame(
            // Videos need a thumbnail: the image loader can't decode a video stream.
            model = rememberFrameModel(item.uri, item.isVideo),
            contentDescription = buildString {
                append(if (item.isVideo) "Video " else "Photo ")
                append(item.displayName)
                if (item.size > 0) append(", ${Fmt.bytes(item.size)}")
            },
            aspectRatio = 4f / 3f,
            isVideo = item.isVideo,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Unexposed frames while the shared items are being read; no spinner. */
@Composable
private fun PlaceholderStrip(count: Int) {
    val c = PherryTheme.colors
    ScrollingStrip(
        count = count,
        frameWidth = 112.dp,
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
}
