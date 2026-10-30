package com.moneymanager.ui.components.imports

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.ui.components.LoadingTextButton
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The shell every bulk "Import all"/"Re-import all" dialog wears: a modal that refuses to dismiss
 * mid-run, a title that flips once the run has a result, a body that swaps the input [form] for the
 * finished [report], a confirm button that is a spinner while running and a "Done" afterwards, and a
 * "Cancel" that disappears once there is nothing left to cancel.
 *
 * Only the shell lives here. What the caller collects before the run and what it says afterwards are
 * genuinely per-source (a staged file resolves its strategy and maybe a source account,
 * the API flow has no form at all), so they stay with their own screen as [form] and [report].
 *
 * [result] doubles as the "finished" flag: non-null means the run completed and its outcome is what
 * [report] renders. The dialog times the run, so a slow bulk import says how slow.
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
    val elapsed = runTimer(isRunning)
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
                elapsed?.let { RunDuration(it, isRunning) }
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

/** How long the latest run took (or has taken so far); null until a run starts. */
@Composable
private fun runTimer(isRunning: Boolean): Duration? {
    var elapsed by remember { mutableStateOf<Duration?>(null) }
    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect
        val start = TimeSource.Monotonic.markNow()
        try {
            while (true) {
                elapsed = start.elapsedNow()
                delay(1.seconds)
            }
        } finally {
            // The run ending cancels this effect; record the exact total, not the last tick.
            elapsed = start.elapsedNow()
        }
    }
    return elapsed
}

@Composable
private fun RunDuration(
    elapsed: Duration,
    isRunning: Boolean,
) {
    val shown = elapsed.inWholeSeconds.seconds
    Text(
        text = if (isRunning) "Elapsed: $shown" else "Took $shown",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
}
