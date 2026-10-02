package com.appharbor.pherry.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.appharbor.pherry.ui.theme.PherryTheme
import com.appharbor.pherry.ui.theme.Spacing

/**
 * The job ticket that rises from the bottom while frames are marked: how many, how heavy, where
 * they go. [actionLabel] is null when nothing can be sent yet (no computer), and [hint] says why.
 */
@Composable
fun SelectionTicket(
    count: Int,
    detail: String,
    actionLabel: String?,
    onAction: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    onHint: (() -> Unit)? = null,
    applyNavigationPadding: Boolean = true,
    actionEnabled: Boolean = true,
) {
    val c = PherryTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(c.envelope)
            .then(if (applyNavigationPadding) Modifier.navigationBarsPadding() else Modifier),
    ) {
        Perforation(color = c.onEnvelope.copy(alpha = 0.35f), modifier = Modifier.padding(horizontal = Spacing.lg))
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Spacing.xs, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear, enabled = actionEnabled) {
                PhIcon(Ph.X, contentDescription = "Clear selection", tint = c.onEnvelope)
            }
            Column(Modifier.weight(1f)) {
                // Polite live region: TalkBack hears the ticket arrive and each count change.
                Text(
                    "${Fmt.count(count)} selected",
                    style = MaterialTheme.typography.titleMedium,
                    color = c.onEnvelope,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(detail.uppercase(), style = PherryTheme.text.monoCaps, color = c.onEnvelope2)
            }
            Spacer(Modifier.width(Spacing.sm))
            if (actionLabel != null) {
                PrintButton(text = actionLabel, onClick = onAction, icon = Ph.Send, onEnvelope = true, enabled = actionEnabled)
            } else if (hint != null && onHint != null) {
                PrintButton(text = hint, onClick = onHint, style = PrintButtonStyle.Outline, onEnvelope = true)
            }
        }
    }
}
