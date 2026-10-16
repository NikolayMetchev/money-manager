package com.moneymanager.ui.screens.qif

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
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.importdirectory.ImportDirectoryService
import com.moneymanager.importdirectory.QifReimportAllPlan
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.tasks.Progress
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.CurrencyPicker
import com.moneymanager.ui.components.imports.BulkImportDialogScaffold
import com.moneymanager.ui.components.imports.BulkImportProgressIndicator
import com.moneymanager.ui.components.imports.BulkMergeReport
import kotlinx.coroutines.launch

/**
 * Re-imports every already-imported QIF file in one go so current strategy/mapping changes apply
 * retroactively (duplicate accounts merged, changed values updated, never-imported/errored records
 * imported, emptied import-created accounts removed). Each file's strategy is the one it was last
 * imported with, falling back to content-aware auto-selection (files with no match are skipped). QIF
 * carries no currency, so the one used at import time is chosen here (default preselected) — the same
 * one avoids spurious value updates. The optional source account is used only when re-running a file's
 * not-yet-imported/errored records. Single confirm + aggregated summary.
 */
@Composable
fun QifReimportAllDialog(
    imported: List<QifImport>,
    importDirectoryService: ImportDirectoryService,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyReadRepository,
    personRepository: PersonReadRepository,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()

    var sourceAccountId by remember { mutableStateOf<AccountId?>(null) }
    var selectedCurrencyId by remember { mutableStateOf<CurrencyId?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    var result by remember { mutableStateOf<BulkReimportResult?>(null) }

    var plan by remember { mutableStateOf<QifReimportAllPlan?>(null) }
    LaunchedEffect(imported) {
        val planned = importDirectoryService.planQifReimportAll(imported)
        if (sourceAccountId == null) sourceAccountId = planned.defaultSourceAccountId
        if (selectedCurrencyId == null) selectedCurrencyId = planned.defaultCurrencyId
        plan = planned
    }

    BulkImportDialogScaffold(
        pendingTitle = "Re-import all imported files",
        completeTitle = "Re-import complete",
        result = result,
        isRunning = isRunning,
        confirmLabel = "Re-import ${imported.size} files",
        confirmEnabled = plan != null && selectedCurrencyId != null,
        onRun = {
            val ready = plan ?: return@BulkImportDialogScaffold
            val currencyId = selectedCurrencyId ?: return@BulkImportDialogScaffold
            isRunning = true
            scope.launch {
                try {
                    result = importDirectoryService.applyQifReimportAll(ready, sourceAccountId, currencyId, onProgress = { progress = it })
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
        CurrencyPicker(
            selectedCurrencyId = selectedCurrencyId,
            onCurrencySelected = { selectedCurrencyId = it },
            label = "Currency (as used at import time)",
            currencyRepository = currencyRepository,
            enabled = !isRunning,
            isError = selectedCurrencyId == null,
        )
        Spacer(modifier = Modifier.height(12.dp))
        AccountPicker(
            selectedAccountId = sourceAccountId,
            onAccountSelected = { sourceAccountId = it },
            label = "Source account (for not-yet-imported records)",
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            personRepository = personRepository,
            enabled = !isRunning,
        )
        progress?.let {
            Spacer(modifier = Modifier.height(12.dp))
            BulkImportProgressIndicator(it)
        }
    }
}
