package com.moneymanager.ui.components.imports

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.moneymanager.ui.components.LoadingTextButton

/**
 * The shell every bulk "Import all"/"Re-import all" dialog wears: a modal that refuses to dismiss
 * mid-run, a title that flips once the run has a result, a body that swaps the input [form] for the
 * finished [report], a confirm button that is a spinner while running and a "Done" afterwards, and a
 * "Cancel" that disappears once there is nothing left to cancel.
 *
 * Only the shell lives here. What the caller collects before the run and what it says afterwards are
 * genuinely per-source (QIF picks a currency and a source account, CSV resolves each file's strategy,
 * the API flow has no form at all), so they stay with their own screen as [form] and [report].
 *
 * [result] doubles as the "finished" flag: non-null means the run completed and its outcome is what
 * [report] renders.
 */
@Composable
fun <R : Any> BulkImportDialogScaffold(
    pendingTitle: String,
    completeTitle: String,
    result: R?,
    isRunning: Boolean,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onRun: () -> Unit,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    report: @Composable ColumnScope.(R) -> Unit,
    form: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        title = { Text(if (result != null) completeTitle else pendingTitle) },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
                if (result != null) report(result) else form()
            }
        },
        confirmButton = {
            if (result != null) {
                TextButton(onClick = onComplete) { Text("Done") }
            } else {
                LoadingTextButton(
                    onClick = onRun,
                    enabled = !isRunning && confirmEnabled,
                    loading = isRunning,
                    label = confirmLabel,
                )
            }
        },
        dismissButton = {
            if (result == null) {
                TextButton(onClick = onDismiss, enabled = !isRunning) { Text("Cancel") }
            }
        },
    )
}
