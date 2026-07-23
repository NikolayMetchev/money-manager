package com.moneymanager.ui.screens.csv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.csvimporter.BulkImportProgress

/**
 * One determinate bar for a whole bulk import run: a file counter + current file/phase label over a
 * `LinearProgressIndicator` driven by the row-weighted [BulkImportProgress.overallFraction], so the bar
 * advances within large files instead of jumping once per file. Shared by the CSV and QIF
 * "Import all"/"Re-import all" dialogs (the QIF module already reuses this package's composables).
 */
@Composable
fun BulkImportProgressIndicator(progress: BulkImportProgress) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val label =
            buildString {
                if (progress.filesDone >= progress.filesTotal) {
                    append("Finishing up")
                    append(" — ${progress.rowsDone} of ${progress.rowsTotal} rows")
                } else {
                    append("File ${progress.filesDone + 1} of ${progress.filesTotal}")
                    append(" — ${progress.rowsDone} of ${progress.rowsTotal} rows")
                    progress.currentFileName?.let { append(" — $it") }
                    progress.detail?.let { append(": $it") }
                }
            }
        Text(
            text = "$label…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(progress = { progress.overallFraction }, modifier = Modifier.fillMaxWidth())
    }
}
