package com.appharbor.pherry.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.appharbor.pherry.data.upload.SyncPlan
import com.appharbor.pherry.ui.theme.Spacing

/**
 * Confirmation for running a [SyncPlan]. Spells out the upload count and — separately, in the error
 * colour — how many files will be removed from the desktop, since that half is destructive. When
 * [confirmDestructive] is off and the plan has no deletions, it auto-confirms (a pure upload needs
 * no extra tap). Shared by the Library sync button and the Home dashboard so both read identically.
 */
@Composable
fun SyncConfirmDialog(
    plan: SyncPlan,
    confirmDestructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(plan, confirmDestructive) {
        if (!confirmDestructive && plan.deleteCount == 0 && !plan.isNoOp) {
            onConfirm()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (plan.isNoOp) "Already in sync" else "Sync to desktop") },
        text = {
            if (plan.isNoOp) {
                Text("Your desktop already matches this phone — nothing to upload or remove.")
            } else {
                Column {
                    if (plan.uploadCount > 0) {
                        Text("• Upload ${plan.uploadCount} new file(s) (${formatBytes(plan.uploadBytes)}).")
                    }
                    if (plan.deleteCount > 0) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "• Delete ${plan.deleteCount} file(s) from the desktop that you removed from this phone.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                plan.isNoOp -> TextButton(onClick = onConfirm) { Text("OK") }
                plan.deleteCount > 0 -> FilledTonalButton(
                    onClick = onConfirm,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) { Text("Sync & Delete") }
                else -> TextButton(onClick = onConfirm) { Text("Sync") }
            }
        },
        dismissButton = if (plan.isNoOp) null else {
            { TextButton(onClick = onDismiss) { Text("Cancel") } }
        },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
