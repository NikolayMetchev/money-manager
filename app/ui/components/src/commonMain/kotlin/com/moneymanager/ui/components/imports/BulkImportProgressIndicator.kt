package com.moneymanager.ui.components.imports

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
import com.moneymanager.humanreadable.HumanReadable
import com.moneymanager.tasks.Progress

/**
 * One determinate bar for a whole bulk import run: the run's detail (file counter, current file and
 * phase) and how far through its rows it is ("10% of 300K rows") over a `LinearProgressIndicator` driven by the row-weighted fraction, so the bar
 * advances within large files instead of jumping once per file. Shared by the staged-file
 * "Import all"/"Re-import all" dialogs.
 */
@Composable
fun BulkImportProgressIndicator(progress: Progress) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val label =
            buildString {
                append(progress.detail)
                val total = progress.total
                if (total != null) {
                    append(" — ${((progress.fraction ?: 0f) * PERCENT).toInt()}% of ${HumanReadable.count(total.toLong())} rows")
                }
            }
        Text(
            text = "$label…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(progress = { progress.fraction ?: 0f }, modifier = Modifier.fillMaxWidth())
    }
}

private const val PERCENT = 100
