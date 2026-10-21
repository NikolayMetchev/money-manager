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
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.importdirectory.CsvImportAllPlan
import com.moneymanager.importdirectory.ImportDirectoryService
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.tasks.Progress
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.imports.BulkImportDialogScaffold
import com.moneymanager.ui.components.imports.BulkImportProgressIndicator
import kotlinx.coroutines.launch

/**
 * Applies the matching strategy to every unimported CSV file in one go. Each file's strategy is
 * auto-matched from its column headers (files with no match are skipped). The shared source account
 * chosen here is used only for files whose strategy needs a user-chosen source; strategies that
 * hard-code or per-row-resolve the source ignore it. Payee accounts auto-create with detected names.
 * Mirrors QifImportAllDialog by design (CSV has no currency choice — currency comes from the strategy).
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
) {
    val scope = rememberSchemaAwareCoroutineScope()

    var sourceAccountId by remember { mutableStateOf<AccountId?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }

    // Each file's matched strategy and its import directory's account (which takes priority over the shared
    // picker below, so two folders of identically-formatted files each land on their own account). Lets the
    // dialog say how many files will be skipped and whether a shared source account is needed.
    var plan by remember { mutableStateOf<CsvImportAllPlan?>(null) }
    // A run changes the file list it was planned from; re-planning then would only swap in an empty plan.
    LaunchedEffect(unimported) {
        if (isImporting || summary != null) return@LaunchedEffect
        plan = importDirectoryService.planCsvImportAll(unimported)
    }
    val skippedNoStrategyCount = plan?.skippedNoStrategy ?: 0
    val needsSourceAccount = plan?.needsSourceAccount == true

    BulkImportDialogScaffold(
        pendingTitle = "Import all unimported files",
        completeTitle = "Import complete",
        result = summary,
        isRunning = isImporting,
        confirmLabel = "Import ${unimported.size} files",
        confirmEnabled = plan != null && (!needsSourceAccount || sourceAccountId != null),
        onRun = {
            val ready = plan ?: return@BulkImportDialogScaffold
            isImporting = true
            scope.launch {
                try {
                    summary = importDirectoryService.applyCsvImportAll(ready, sourceAccountId, onProgress = { progress = it }).toSummary()
                } finally {
                    isImporting = false
                }
            }
        },
        onDismiss = onDismiss,
        onComplete = onComplete,
        report = { Text(it, style = MaterialTheme.typography.bodyMedium) },
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
