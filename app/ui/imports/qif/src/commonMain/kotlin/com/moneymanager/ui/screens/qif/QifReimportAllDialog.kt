package com.moneymanager.ui.screens.qif

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.QifImportReadRepository
import com.moneymanager.domain.repository.SettingsReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.qifimporter.QifBulkReimportResult
import com.moneymanager.qifimporter.bulkReimportQif
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.CurrencyPicker
import com.moneymanager.ui.components.LoadingTextButton
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.screens.csv.BulkMergeReport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-imports every already-imported QIF file in one go so current strategy/mapping changes apply
 * retroactively (duplicate accounts merged, changed values updated, never-imported/errored records
 * imported, emptied import-created accounts removed). Each file's strategy is the one it was last
 * imported with, falling back to content-aware auto-selection (files with no match are skipped). QIF
 * carries no currency, so the one used at import time is chosen here (default preselected) — the same
 * one avoids spurious value updates. The optional source account is used only when re-running a file's
 * not-yet-imported/errored records. Single confirm + aggregated summary. Mirrors [QifImportAllDialog].
 */
@Composable
@Suppress("LongParameterList", "LongMethod", "DuplicatedCode")
fun QifReimportAllDialog(
    imported: List<QifImport>,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyReadRepository,
    personRepository: PersonReadRepository,
    qifImportRepository: QifImportReadRepository,
    transactionRepository: TransactionReadRepository,
    transferSourceRepository: TransferSourceReadRepository,
    settingsRepository: SettingsReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val strategies by csvImportStrategyRepository.getAllStrategies().collectAsStateWithSchemaErrorHandling(emptyList())
    val currencies by currencyRepository.getAllCurrencies().collectAsStateWithSchemaErrorHandling(emptyList())
    val accounts by accountRepository.getAllAccounts().collectAsStateWithSchemaErrorHandling(emptyList())

    var sourceAccountId by remember { mutableStateOf<AccountId?>(null) }
    var selectedCurrencyId by remember { mutableStateOf<CurrencyId?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var result by remember { mutableStateOf<QifBulkReimportResult?>(null) }

    LaunchedEffect(accounts) {
        if (sourceAccountId == null) {
            val last = settingsRepository.getLastQifAccountId().first()
            if (last != null && accounts.any { it.id == last }) sourceAccountId = last
        }
    }
    LaunchedEffect(currencies) {
        if (selectedCurrencyId == null && currencies.isNotEmpty()) {
            val defaultId = settingsRepository.getDefaultCurrencyId().first()
            selectedCurrencyId = defaultId?.takeIf { id -> currencies.any { it.id == id } } ?: currencies.firstOrNull()?.id
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        title = { Text(if (result != null) "Re-import complete" else "Re-import all imported files") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
                val currentResult = result
                if (currentResult != null) {
                    Text(currentResult.toSummary(), style = MaterialTheme.typography.bodyMedium)
                    BulkMergeReport(currentResult.merges, currentResult.reversals, currentResult.skipped)
                } else {
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
                    progress?.let { (done, total) ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Re-importing ${done.coerceAtMost(total)} of $total…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (result != null) {
                TextButton(onClick = onComplete) { Text("Done") }
            } else {
                LoadingTextButton(
                    onClick = {
                        if (selectedCurrencyId == null) return@LoadingTextButton
                        isRunning = true
                        scope.launch {
                            try {
                                result =
                                    bulkReimportQif(
                                        imports = imported,
                                        sourceAccountOverride = sourceAccountId,
                                        currencyId = selectedCurrencyId,
                                        strategies = strategies,
                                        currencies = currencies,
                                        accountMappingRepository = accountMappingRepository,
                                        accountRepository = accountRepository,
                                        qifImportRepository = qifImportRepository,
                                        transactionRepository = transactionRepository,
                                        transferSourceRepository = transferSourceRepository,
                                        maintenance = maintenance,
                                        importEngine = importEngine,
                                        onProgress = { done, total -> progress = done to total },
                                    )
                            } finally {
                                isRunning = false
                            }
                        }
                    },
                    enabled = !isRunning && selectedCurrencyId != null,
                    loading = isRunning,
                    label = "Re-import ${imported.size} files",
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
