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
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.importdirectory.ImportDirectoryService
import com.moneymanager.importdirectory.QifImportAllPlan
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.tasks.Progress
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.imports.BulkImportDialogScaffold
import com.moneymanager.ui.components.imports.BulkImportProgressIndicator
import kotlinx.coroutines.launch

/**
 * Applies the matching QIF strategy to every unimported file in one go, using a single source account
 * chosen here. The currency is taken from each file's auto-detected strategy (so there is no currency
 * prompt). The source account defaults to (and is remembered as) the last account used for QIF imports.
 * Payee accounts auto-create with their detected names.
 */
@Composable
fun QifImportAllDialog(
    unimported: List<QifImport>,
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

    // Each file's import directory account takes priority over the shared picker below, so two folders of
    // identically-formatted files each land on their own account without user disambiguation.
    var plan by remember { mutableStateOf<QifImportAllPlan?>(null) }
    // A run changes the file list it was planned from; re-planning then would only swap in an empty plan.
    LaunchedEffect(unimported) {
        if (isImporting || summary != null) return@LaunchedEffect
        val planned = importDirectoryService.planQifImportAll(unimported)
        if (sourceAccountId == null) sourceAccountId = planned.defaultSourceAccountId
        plan = planned
    }
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
                    summary = importDirectoryService.applyQifImportAll(ready, sourceAccountId, onProgress = { progress = it }).toSummary()
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
                label = "Source account (for files whose folder has none set)",
                accountRepository = accountRepository,
                categoryRepository = categoryRepository,
                personRepository = personRepository,
                enabled = !isImporting,
                isError = sourceAccountId == null,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        progress?.let {
            Spacer(modifier = Modifier.height(12.dp))
            BulkImportProgressIndicator(it)
        }
    }
}
