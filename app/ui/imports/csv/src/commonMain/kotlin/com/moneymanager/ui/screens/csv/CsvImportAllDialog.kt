package com.moneymanager.ui.screens.csv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.moneymanager.csvimporter.BulkImportProgress
import com.moneymanager.csvimporter.STRATEGY_CONTENT_SAMPLE_SIZE
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.csvimporter.needsSourceAccountOverride
import com.moneymanager.csvimporter.selectForCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.LoadingTextButton
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Applies the matching strategy to every unimported CSV file in one go. Each file's strategy is
 * auto-matched from its column headers (files with no match are skipped). The shared source account
 * chosen here is used only for files whose strategy needs a user-chosen source; strategies that
 * hard-code or per-row-resolve the source ignore it. Payee accounts auto-create with detected names.
 * Mirrors QifImportAllDialog by design (CSV has no currency choice — currency comes from the strategy).
 */
@Composable
@Suppress("LongParameterList", "LongMethod", "DuplicatedCode")
fun CsvImportAllDialog(
    unimported: List<CsvImport>,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyReadRepository,
    cryptoRepository: CryptoReadRepository,
    personRepository: PersonReadRepository,
    passThroughAccountRepository: PassThroughAccountReadRepository,
    csvImportRepository: CsvImportReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val strategies by csvImportStrategyRepository.getAllStrategies().collectAsStateWithSchemaErrorHandling(emptyList())
    val currencies by currencyRepository.getAllCurrencies().collectAsStateWithSchemaErrorHandling(emptyList())
    val passThroughAccounts by passThroughAccountRepository.getAll().collectAsStateWithSchemaErrorHandling(emptyList())

    var sourceAccountId by remember { mutableStateOf<AccountId?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<BulkImportProgress?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }

    // Match each file's strategy (filename + content aware), so we can tell how many files will be
    // skipped (no matching strategy) and whether any matched strategy needs a user-chosen source
    // account. getAllImports() doesn't populate columns, so load each file's columns before matching.
    var matchedStrategies by remember { mutableStateOf<List<CsvImportStrategy?>?>(null) }
    LaunchedEffect(unimported, strategies) {
        if (strategies.isEmpty()) {
            matchedStrategies = null
            return@LaunchedEffect
        }
        matchedStrategies =
            unimported.map { listedImport ->
                val fullImport = csvImportRepository.getImport(listedImport.id).first() ?: return@map null
                val sampleRows = csvImportRepository.getImportRows(listedImport.id, limit = STRATEGY_CONTENT_SAMPLE_SIZE, offset = 0)
                strategies.selectForCsv(fullImport.originalFileName, fullImport.columns, sampleRows)
            }
    }
    val matches = matchedStrategies
    val skippedNoStrategyCount = if (matches == null) 0 else unimported.size - matches.count { it != null }
    val needsSourceAccount = matches?.any { it != null && it.needsSourceAccountOverride() } ?: false

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text(if (summary != null) "Import complete" else "Import all unimported files") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val currentSummary = summary
                if (currentSummary != null) {
                    Text(currentSummary, style = MaterialTheme.typography.bodyMedium)
                } else {
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
        },
        confirmButton = {
            if (summary != null) {
                TextButton(onClick = onComplete) { Text("Done") }
            } else {
                LoadingTextButton(
                    onClick = {
                        if (needsSourceAccount && sourceAccountId == null) return@LoadingTextButton
                        isImporting = true
                        scope.launch {
                            try {
                                val result =
                                    bulkApplyCsv(
                                        imports = unimported,
                                        sourceAccountOverride = sourceAccountId,
                                        strategies = strategies,
                                        currencies = currencies,
                                        accountMappingRepository = accountMappingRepository,
                                        accountRepository = accountRepository,
                                        csvImportRepository = csvImportRepository,
                                        maintenance = maintenance,
                                        importEngine = importEngine,
                                        onProgress = { progress = it },
                                        passThroughAccounts = passThroughAccounts,
                                        cryptoRepository = cryptoRepository,
                                    )
                                summary = result.toSummary()
                            } finally {
                                isImporting = false
                            }
                        }
                    },
                    enabled = !isImporting && (!needsSourceAccount || sourceAccountId != null),
                    loading = isImporting,
                    label = "Import ${unimported.size} files",
                )
            }
        },
        dismissButton = {
            if (summary == null) {
                TextButton(onClick = onDismiss, enabled = !isImporting) { Text("Cancel") }
            }
        },
    )
}
