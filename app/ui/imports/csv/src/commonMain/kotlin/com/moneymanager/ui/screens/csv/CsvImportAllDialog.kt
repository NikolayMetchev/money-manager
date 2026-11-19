package com.moneymanager.ui.screens.csv

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import co.touchlab.kermit.Logger
import com.moneymanager.csvimporter.BulkApplyResult
import com.moneymanager.csvimporter.BulkFileStatus
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.importdirectory.CsvImportAllPlan
import com.moneymanager.importdirectory.ImportDirectoryService
import com.moneymanager.presenter.error.SchemaErrorDetector
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.tasks.Progress
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.imports.BulkImportDialogScaffold
import com.moneymanager.ui.components.imports.BulkImportProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val logger = Logger.withTag("CsvImportAllDialog")

/**
 * Applies the matching strategy to every unimported CSV file in one go. Each file's strategy is
 * auto-matched from its column headers (files with no match are skipped). The shared source account
 * chosen here is used only for files whose strategy needs a user-chosen source; strategies that
 * hard-code or per-row-resolve the source ignore it. Payee accounts auto-create with detected names.
 * CSV has no currency choice here — currency comes from the strategy. Progress names each file by [fileLabel]
 * (its path in its import folder), and Cancel stops the run once the file under way is done.
 */
@Composable
fun CsvImportAllDialog(
    unimported: List<CsvImport>,
    importDirectoryService: ImportDirectoryService,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    fileLabel: (CsvImport) -> String = { it.originalFileName },
) {
    val scope = rememberSchemaAwareCoroutineScope()

    var stopRequested by remember { mutableStateOf(false) }
    var sourceAccountId by remember { mutableStateOf<AccountId?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    var result by remember { mutableStateOf<Result<BulkApplyResult>?>(null) }

    // Each file's matched strategy and its import directory's account (which takes priority over the shared
    // picker below, so two folders of identically-formatted files each land on their own account). Lets the
    // dialog say how many files will be skipped and whether a shared source account is needed.
    var plan by remember { mutableStateOf<CsvImportAllPlan?>(null) }
    // A run changes the file list it was planned from; re-planning then would only swap in an empty plan.
    LaunchedEffect(unimported) {
        if (isImporting || result != null) return@LaunchedEffect
        plan = importDirectoryService.planCsvImportAll(unimported)
    }
    val skippedNoStrategyCount = plan?.skippedNoStrategy ?: 0
    val needsSourceAccount = plan?.needsSourceAccount == true
    val filesById = remember(unimported) { unimported.associateBy { it.id.toString() } }

    BulkImportDialogScaffold(
        pendingTitle = "Import all unimported files",
        completeTitle = if (result?.isFailure == true) "Import interrupted" else "Import complete",
        result = result,
        isRunning = isImporting,
        confirmLabel = if (unimported.size == 1) "Import 1 file" else "Import ${unimported.size} files",
        confirmEnabled = plan != null && (!needsSourceAccount || sourceAccountId != null),
        onRun = {
            val ready = plan ?: return@BulkImportDialogScaffold
            isImporting = true
            stopRequested = false
            scope.launch {
                try {
                    result =
                        Result.success(
                            importDirectoryService.applyCsvImportAll(
                                ready,
                                sourceAccountId,
                                onProgress = { progress = it },
                                fileLabel = fileLabel,
                                stopRequested = { stopRequested },
                            ),
                        )
                } catch (expected: CancellationException) {
                    throw expected
                } catch (expected: Exception) {
                    if (SchemaErrorDetector.isSchemaError(expected)) throw expected
                    logger.e(expected) { "Bulk file import interrupted" }
                    result = Result.failure(expected)
                } finally {
                    isImporting = false
                }
            }
        },
        onDismiss = onDismiss,
        onComplete = onComplete,
        report = { outcome ->
            outcome.fold(
                onSuccess = { applied ->
                    Text(applied.toSummary(), style = MaterialTheme.typography.bodyMedium)
                    applied.files.forEach { file ->
                        val reason =
                            when (file.status) {
                                BulkFileStatus.SKIPPED_NO_STRATEGY -> "No matching strategy"
                                BulkFileStatus.NOTHING_TO_IMPORT -> "No rows left to import"
                                BulkFileStatus.FAILED -> file.error ?: "Import failed"
                                BulkFileStatus.IMPORTED -> null
                            }
                        if (reason != null) {
                            val label = filesById[file.fileId]?.let(fileLabel) ?: file.fileName
                            Text("$label · $reason", modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                },
                onFailure = {
                    Text("The import stopped before it finished. Files already imported may have been saved.")
                    it.message?.let { message -> Text(message, modifier = Modifier.padding(top = 8.dp)) }
                },
            )
        },
        onStop = { stopRequested = true },
        stopping = stopRequested,
    ) {
        if (needsSourceAccount) {
            AccountPicker(
                selectedAccountId = sourceAccountId,
                onAccountSelected = { sourceAccountId = it },
                label = "Source account (for files whose strategy needs one)",
                accountRepository = accountRepository,
                categoryRepository = categoryRepository,
                personRepository = personRepository,
                enabled = !isImporting,
                isError = sourceAccountId == null,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (skippedNoStrategyCount > 0) {
            val skippedFiles =
                if (skippedNoStrategyCount == 1) "1 file has" else "$skippedNoStrategyCount files have"
            Text(
                text = "$skippedFiles no matching strategy and will be skipped.",
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
