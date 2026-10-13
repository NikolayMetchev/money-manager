package com.moneymanager.ui.screens.csv

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.csvimporter.BulkReimportResult
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.importdirectory.CsvReimportAllPlan
import com.moneymanager.importdirectory.ImportDirectoryService
import com.moneymanager.tasks.Progress
import com.moneymanager.ui.components.imports.BulkImportDialogScaffold
import com.moneymanager.ui.components.imports.BulkImportProgressIndicator
import com.moneymanager.ui.components.imports.BulkMergeReport
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch

/**
 * Re-imports every already-imported CSV file in one go so current strategy/mapping changes apply
 * retroactively (duplicate accounts merged, changed transfer values updated, never-imported/errored
 * rows imported, emptied import-created accounts removed). Each file's strategy is the one it was last
 * imported with, falling back to content-aware auto-selection (files with no match are skipped). A
 * file whose strategy needs a source account (no SOURCE_ACCOUNT mapping) always resolves it from its
 * own import history (`CsvImportReadRepository.historicalSourceAccounts`) rather than a shared,
 * dialog-level pick — different files can (and do) belong to different accounts, so one shared choice
 * across the whole batch never made sense here. Single confirm + aggregated summary — no per-file
 * preview.
 */
@Composable
fun CsvReimportAllDialog(
    imported: List<CsvImport>,
    importDirectoryService: ImportDirectoryService,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()

    var isRunning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    var result by remember { mutableStateOf<BulkReimportResult?>(null) }

    // Resolves each file's strategy the same way the re-import will, so the dialog can say how many files
    // will be skipped (no matching strategy).
    var plan by remember { mutableStateOf<CsvReimportAllPlan?>(null) }
    LaunchedEffect(imported) { plan = importDirectoryService.planCsvReimportAll(imported) }
    val skippedNoStrategyCount = plan?.skippedNoStrategy ?: 0

    BulkImportDialogScaffold(
        pendingTitle = "Re-import all imported files",
        completeTitle = "Re-import complete",
        result = result,
        isRunning = isRunning,
        confirmLabel = "Re-import ${imported.size} files",
        confirmEnabled = plan != null,
        onRun = {
            val ready = plan ?: return@BulkImportDialogScaffold
            isRunning = true
            scope.launch {
                try {
                    result = importDirectoryService.applyCsvReimportAll(ready, onProgress = { progress = it })
                } finally {
                    isRunning = false
                }
            }
        },
        onDismiss = onDismiss,
        onComplete = onComplete,
        report = { finished ->
            Text(finished.toSummary(), style = MaterialTheme.typography.bodyMedium)
            BulkMergeReport(finished.merges, finished.reversals, finished.skipped)
        },
    ) {
        Text(
            text =
                "Applies the current strategy and account mappings to all ${imported.size} " +
                    "imported file${if (imported.size == 1) "" else "s"} retroactively.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (skippedNoStrategyCount > 0) {
            Text(
                text =
                    "$skippedNoStrategyCount file${if (skippedNoStrategyCount == 1) "" else "s"} " +
                        "have no matching strategy and will be skipped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        progress?.let {
            Spacer(modifier = Modifier.height(12.dp))
            BulkImportProgressIndicator(it)
        }
    }
}
