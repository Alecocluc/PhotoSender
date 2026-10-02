package com.appharbor.pherry.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import com.appharbor.pherry.data.model.MediaItem
import com.appharbor.pherry.ui.components.EdgeText
import com.appharbor.pherry.ui.components.Fmt
import com.appharbor.pherry.ui.components.Hairline
import com.appharbor.pherry.ui.components.Ph
import com.appharbor.pherry.ui.components.PhIcon
import com.appharbor.pherry.ui.components.PrintButton
import com.appharbor.pherry.ui.components.PrintButtonStyle
import com.appharbor.pherry.ui.theme.LocalPherryColors
import com.appharbor.pherry.ui.theme.PherryShape
import com.appharbor.pherry.ui.theme.PherryTheme
import com.appharbor.pherry.ui.theme.Spacing

/**
 * The album's frames one at a time on film black, swiped sideways. Covers the album, draws under
 * the system bars, and closes on System Back. Videos show their frame; there is no playback.
 */
@Composable
internal fun AlbumViewer(
    items: List<MediaItem>,
    startIndex: Int,
    completedIds: Set<Long>,
    connected: Boolean,
    onPageChange: (Long) -> Unit,
    onClose: () -> Unit,
    onSend: (MediaItem) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PherryTheme.colors
    BackHandler(onBack = onClose)
    LightSystemBarIcons()

    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
    val latestOnPageChange by rememberUpdatedState(onPageChange)
    LaunchedEffect(pagerState, items) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            items.getOrNull(page)?.let { latestOnPageChange(it.id) }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(c.film)
            // Swallow touches that miss a control so they never reach the album underneath.
            .pointerInput(Unit) {},
    ) {
        val page = pagerState.currentPage.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val current = items.getOrNull(page)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .heightIn(min = 56.dp)
                .padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                PhIcon(Ph.X, contentDescription = "Close", tint = c.filmInk)
            }
            Text(
                text = current?.displayName.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                color = c.filmInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            EdgeText(
                text = "${Fmt.count(page + 1)} / ${Fmt.count(items.size)}",
                dim = true,
                modifier = Modifier.padding(horizontal = Spacing.md),
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            key = { items[it].id },
        ) { index ->
            ViewerPage(items[index])
        }

        if (current != null) {
            val backedUp = current.id in completedIds
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                Hairline(color = c.filmRule)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.screen, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .semantics(mergeDescendants = true) {},
                    ) {
                        EdgeText(
                            listOf(Fmt.bytes(current.size), Fmt.stamp(current.dateModified * 1000L))
                                .filter { it.isNotEmpty() }
                                .joinToString(" · "),
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        if (backedUp) SavedMark() else EdgeText("Not backed up")
                    }
                    Spacer(Modifier.width(Spacing.md))
                    if (connected) {
                        FilmButton(text = "Send this", icon = Ph.Send, onClick = { onSend(current) })
                    } else {
                        FilmButton(text = "Pair a computer", icon = Ph.Desktop, onClick = onConnect, outline = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerPage(item: MediaItem) {
    val c = PherryTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = rememberFrameModel(item.uri, item.isVideo, sizePx = 1080),
            contentDescription = "${item.displayName}, ${if (item.isVideo) "video" else "photo"}",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(PherryShape.print)
                    .background(c.film.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                PhIcon(Ph.Play, contentDescription = null, tint = c.filmInk, size = 28.dp)
            }
        }
    }
}

/**
 * PrintButton prints in ink on paper; on film black that ink would vanish, so the viewer re-inks it:
 * envelope yellow for the job, film ink for the outlined alternative.
 */
@Composable
private fun FilmButton(text: String, @DrawableRes icon: Int, onClick: () -> Unit, outline: Boolean = false) {
    val c = PherryTheme.colors
    CompositionLocalProvider(
        LocalPherryColors provides c.copy(ink = if (outline) c.filmInk else c.envelope, paper = c.onEnvelope),
    ) {
        PrintButton(
            text = text,
            onClick = onClick,
            style = if (outline) PrintButtonStyle.Outline else PrintButtonStyle.Ink,
            icon = icon,
        )
    }
}

/** Light status and navigation bar icons over the black viewer; the previous look returns on close. */
@Composable
private fun LightSystemBarIcons() {
    val window = LocalActivity.current?.window
    val view = LocalView.current
    DisposableEffect(window, view) {
        if (window == null) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        val lightStatus = controller.isAppearanceLightStatusBars
        val lightNavigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = lightStatus
            controller.isAppearanceLightNavigationBars = lightNavigation
        }
    }
}
