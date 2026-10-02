package com.appharbor.pherry.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.appharbor.pherry.data.upload.UpgradeReviewState
import com.appharbor.pherry.ui.theme.Spacing

@Composable
fun UpgradeReviewDialog(
    review: UpgradeReviewState,
    computer: String,
    approving: Boolean,
    error: String?,
    hasMediaAccess: Boolean,
    onApprove: () -> Unit,
    onRetry: () -> Unit,
    onOpenLibrary: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!approving) onDismiss() },
        title = { Text("Review your existing backup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text("Each phone now has its own folder on $computer. Automatic backups stay on hold until you review this change.")
                if (review.legacyFiles > 0) Text("Found ${Fmt.plural(review.legacyFiles, "file")} (${Fmt.bytes(review.legacyBytes)}) from the previous version.", style = MaterialTheme.typography.titleSmall)
                else if (review.localHistoryCount > 0) Text("Your previous transfer history is preserved. Pherry will check the computer before trusting those old records.")
                Text("Pherry will scan all accessible photos and videos on this phone and compare their contents with the old backup. Exact matches will move into this phone's folder. Files with no match on this phone stay where they are; existing files are never overwritten.")
                Text("New or changed originals will upload normally. This can take time for a large library. Auto-backup resumes after this review if you had it enabled.")
                if (!hasMediaAccess) Text("Allow access in the Library first so Pherry can compare your originals.")
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                if (approving || review.checking) Text("Checking the backup and preparing your files...")
            }
        },
        confirmButton = {
            TextButton(enabled = !approving && !review.checking, onClick = {
                when {
                    !hasMediaAccess -> onOpenLibrary()
                    !review.required -> onRetry()
                    else -> onApprove()
                }
            }) { Text(if (!hasMediaAccess) "Open Library" else if (!review.required) "Check again" else "Move verified matches") }
        },
        dismissButton = { TextButton(enabled = !approving, onClick = onDismiss) { Text("Later") } },
    )
}
