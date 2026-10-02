package com.appharbor.pherry.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.appharbor.pherry.data.upload.SyncPlan
import com.appharbor.pherry.ui.theme.PherryShape
import com.appharbor.pherry.ui.theme.PherryTheme
import com.appharbor.pherry.ui.theme.Spacing

/** How many of the files to be deleted the dialog names before "and N more". */
private const val DELETE_PREVIEW = 5

/**
 * Confirmation for running a [SyncPlan]. The upload half and the destructive half are counted on
 * separate lines; the delete line is red, names the first files, and the confirm button names the
 * deletion. When [confirmDestructive] is off and nothing would be deleted, it confirms itself (a pure
 * upload needs no extra tap). Shared by Home and Library so both read identically.
 *
 * Without the pairing token ([canDelete] false) the computer would refuse the delete, so the dialog
 * says so, offers [onScanTicket], and confirming sends only the new files.
 */
@Composable
fun SyncConfirmDialog(
    plan: SyncPlan,
    computerName: String,
    confirmDestructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    canDelete: Boolean = true,
    onScanTicket: (() -> Unit)? = null,
) {
    LaunchedEffect(plan, confirmDestructive) {
        if (!confirmDestructive && plan.deleteCount == 0 && !plan.isNoOp) onConfirm()
    }
    if (!confirmDestructive && plan.deleteCount == 0 && !plan.isNoOp) return

    val c = PherryTheme.colors
    val deletes = plan.deleteCount > 0
    val blockedDeletes = deletes && !canDelete
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.sheet,
        title = {
            Text(
                when {
                    plan.isNoOp && plan.deletesWithheld -> "Nothing to send"
                    plan.isNoOp -> "Already in sync"
                    else -> "Sync with $computerName?"
                }
            )
        },
        text = {
            Column {
                if (plan.isNoOp && !plan.deletesWithheld) {
                    Text("$computerName already matches this phone. Nothing to send or delete.")
                }
                if (plan.uploadCount > 0) {
                    PlanLine(
                        mark = { PhIcon(Ph.Upload, contentDescription = null, tint = c.ink, size = 18.dp) },
                        text = "Send ${Fmt.plural(plan.uploadCount, "new file")} (${Fmt.bytes(plan.uploadBytes)})",
                    )
                }
                if (deletes && canDelete) {
                    if (plan.uploadCount > 0) Spacer(Modifier.height(Spacing.md))
                    PlanLine(
                        mark = {
                            Box(Modifier.size(18.dp).clip(PherryShape.frame).background(c.red), contentAlignment = Alignment.Center) {
                                PhIcon(Ph.XBold, contentDescription = null, tint = MaterialTheme.colorScheme.onError, size = 11.dp)
                            }
                        },
                        text = "Delete ${Fmt.plural(plan.deleteCount, "file")} from $computerName. They're no longer on this phone. This can't be undone.",
                        danger = true,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    DeletePreview(plan)
                }
                if (blockedDeletes) {
                    if (plan.uploadCount > 0) Spacer(Modifier.height(Spacing.md))
                    Notice(
                        title = "To delete files on $computerName, scan its ticket once",
                        detail = "${Fmt.plural(plan.deleteCount, "file")} no longer on this phone stay on $computerName for now.",
                        error = false,
                    )
                }
                if (plan.deletesWithheld) {
                    if (plan.uploadCount > 0) Spacer(Modifier.height(Spacing.md))
                    Notice(
                        title = "Nothing will be deleted",
                        detail = "Some photos aren't visible to Pherry right now, so nothing is deleted from $computerName.",
                        error = false,
                    )
                }
            }
        },
        confirmButton = {
            when {
                plan.isNoOp -> TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = c.ink)) { Text("OK") }
                blockedDeletes && plan.uploadCount > 0 -> TextButton(
                    onClick = onConfirm,
                    colors = ButtonDefaults.textButtonColors(contentColor = c.ink),
                ) { Text("Send ${Fmt.count(plan.uploadCount)}") }
                blockedDeletes && onScanTicket != null -> TextButton(
                    onClick = onScanTicket,
                    colors = ButtonDefaults.textButtonColors(contentColor = c.ink),
                ) { Text("Scan the ticket") }
                blockedDeletes -> TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = c.ink)) { Text("OK") }
                deletes -> TextButton(
                    onClick = onConfirm,
                    colors = ButtonDefaults.textButtonColors(contentColor = c.red),
                ) { Text("Sync and delete ${Fmt.count(plan.deleteCount)}") }
                else -> TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = c.ink)) { Text("Sync") }
            }
        },
        dismissButton = when {
            plan.isNoOp -> null
            blockedDeletes && plan.uploadCount == 0 && onScanTicket == null -> null
            else -> {
                {
                    TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = c.ink)) {
                        Text(if (blockedDeletes && plan.uploadCount == 0) "Close" else "Cancel")
                    }
                }
            }
        },
    )
}

/** Names the first files a sync would delete, so the confirmation shows what goes, not just how many. */
@Composable
private fun DeletePreview(plan: SyncPlan) {
    val c = PherryTheme.colors
    Column(
        Modifier
            .padding(start = 20.dp + Spacing.md)
            .heightIn(max = 160.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        plan.deleteEntries.take(DELETE_PREVIEW).forEach { entry ->
            Text(
                listOf(entry.fileName, entry.bucketName).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = c.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val more = plan.deleteCount - DELETE_PREVIEW
        if (more > 0) {
            Text("and ${Fmt.count(more)} more", style = MaterialTheme.typography.bodySmall, color = c.ink2)
        }
    }
}

@Composable
private fun PlanLine(mark: @Composable () -> Unit, text: String, danger: Boolean = false) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { mark() }
        Spacer(Modifier.width(Spacing.md))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (danger) PherryTheme.colors.red else PherryTheme.colors.ink,
        )
    }
}
