package com.appharbor.pherry.ui.gallery

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.appharbor.pherry.ui.components.EdgeText
import com.appharbor.pherry.ui.components.Fmt
import com.appharbor.pherry.ui.components.Ph
import com.appharbor.pherry.ui.components.PhIcon
import com.appharbor.pherry.ui.theme.PherryShape
import com.appharbor.pherry.ui.theme.PherryTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What to hand a frame's image loader. Photos load straight from their URI; videos use the
 * MediaStore thumbnail, since the image loader can't decode a video stream. Null while it loads
 * (the frame shows bare film).
 */
@Composable
internal fun rememberFrameModel(uri: Uri, isVideo: Boolean, sizePx: Int = 384): Any? {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, uri, isVideo, sizePx) {
        if (isVideo) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null) }.getOrNull()
            }
        }
    }
    return if (isVideo) thumbnail else uri
}

/** Edge-print mark under a frame that is already backed up: a tick and "SAVED". */
@Composable
internal fun SavedMark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        PhIcon(Ph.CheckBold, contentDescription = null, tint = PherryTheme.colors.edge, size = 10.dp)
        Spacer(Modifier.width(3.dp))
        EdgeText("Saved", dim = true)
    }
}

/** An unexposed frame: stands in for a picture while the library is being read. */
@Composable
internal fun BlankFrame(aspectRatio: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(PherryShape.frame)
            .background(PherryTheme.colors.film2),
    )
}

/**
 * The selection ticket's data line: "48 MB · to ALEX-PC", or just the size with no computer.
 * [alreadyThere] picked frames the computer already has are left out of the send, and said so.
 */
internal fun ticketDetail(bytes: Long, connected: Boolean, serverName: String, alreadyThere: Int = 0): String {
    val parts = mutableListOf(Fmt.bytes(bytes))
    if (connected) parts += "to ${serverName.ifBlank { "your computer" }}"
    if (alreadyThere > 0) parts += "${Fmt.count(alreadyThere)} already there"
    return parts.joinToString(" · ")
}

/** The ticket's action: counts only what will actually be sent, unless all of it is already there. */
internal fun sendLabel(count: Int, alreadyThere: Int): String {
    val toSend = count - alreadyThere
    return "Send ${Fmt.count(if (toSend > 0) toSend else count)}"
}

/** Snackbar when everything picked is already on the computer, so nothing was sent. */
internal fun alreadySentMessage(count: Int, serverName: String): String {
    val computer = serverName.ifBlank { "your computer" }
    return if (count == 1) "Already on $computer" else "All ${Fmt.count(count)} are already on $computer"
}

/**
 * [value] while [active]; otherwise the last value seen while active. Lets a panel slide away
 * still showing what it held (the selection ticket after its selection is cleared).
 */
@Composable
internal fun <T> heldWhile(active: Boolean, value: T): T {
    val held = remember { HeldValue(value) }
    if (active) held.value = value
    return held.value
}

private class HeldValue<T>(var value: T)
