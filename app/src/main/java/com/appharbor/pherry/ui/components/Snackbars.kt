package com.appharbor.pherry.ui.components

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.appharbor.pherry.ui.theme.PherryShape
import com.appharbor.pherry.ui.theme.PherryTheme

/** App-wide snackbar host; screens post transient results here instead of Toasts. */
val LocalSnackbarHost = staticCompositionLocalOf { SnackbarHostState() }

/** Snackbar printed in ink, its action in envelope yellow. */
@Composable
fun PherrySnackbar(data: SnackbarData) {
    val c = PherryTheme.colors
    Snackbar(
        snackbarData = data,
        shape = PherryShape.print,
        containerColor = c.ink,
        contentColor = c.paper,
        actionColor = c.envelope,
        dismissActionContentColor = c.paper,
    )
}
