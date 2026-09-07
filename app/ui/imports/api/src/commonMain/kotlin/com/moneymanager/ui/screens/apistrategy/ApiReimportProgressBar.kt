package com.moneymanager.ui.screens.apistrategy

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
import com.moneymanager.importengineapi.ImportProgress

/**
 * The phase text plus bar shown while an API re-import runs. Shared by the single-session and
 * bulk dialogs so both read the same, and determinate whenever the run knows its fraction —
 * a re-import is long enough that a spinner alone leaves the user guessing.
 */
@Composable
internal fun ApiReimportProgress(
    progress: ImportProgress,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "${progress.detail}…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        val fraction = progress.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        val processed = progress.processed
        val total = progress.total
        if (processed != null && total != null && total > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$processed of $total",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
