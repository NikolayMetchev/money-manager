@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens.csv

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
import com.moneymanager.csvimporter.CsvBulkReimportResult
import com.moneymanager.csvimporter.STRATEGY_CONTENT_SAMPLE_SIZE
import com.moneymanager.csvimporter.bulkReimportCsv
import com.moneymanager.csvimporter.needsSourceAccountOverride
import com.moneymanager.csvimporter.selectForCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.LoadingTextButton
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-imports every already-imported CSV file in one go so current strategy/mapping changes apply
 * retroactively (duplicate accounts merged, changed transfer values updated, never-imported/errored
 * rows imported, emptied import-created accounts removed). Each file's strategy is the one it was last
 * imported with, falling back to content-aware auto-selection (files with no match are skipped). The
 * shared source account chosen here is used only for files whose strategy needs a user-chosen source
 * when re-running not-yet-imported/errored rows. Single confirm + aggregated summary — no per-file
 * preview. Mirrors [CsvImportAllDialog] by design.
 */
@Composable
@Suppress("LongParameterList", "LongMethod", "DuplicatedCode")
fun CsvReimportAllDialog(
    imported: List<CsvImport>,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyReadRepository,
    personRepository: PersonReadRepository,
    passThroughAccountRepository: PassThroughAccountReadRepository,
    csvImportRepository: CsvImportReadRepository,
    transactionRepository: TransactionReadRepository,
    transferRelationshipRepository: TransferRelationshipReadRepository,
    transferSourceRepository: TransferSourceReadRepository,
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
    var isRunning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var result by remember { mutableStateOf<CsvBulkReimportResult?>(null) }

    // Resolve each file's strategy the same way the re-import will: the one it was last imported with,
    // falling back to content-aware auto-selection. Lets us report how many files will be skipped (no
    // strategy) and whether any needs a user-chosen source. getAllImports() omits columns, so re-fetch.
    var matchedStrategies by remember { mutableStateOf<List<CsvImportStrategy?>?>(null) }
    LaunchedEffect(imported, strategies) {
        if (strategies.isEmpty()) {
            matchedStrategies = null
            return@LaunchedEffect
        }
        matchedStrategies =
            imported.map { listedImport ->
                val fullImport = csvImportRepository.getImport(listedImport.id).first() ?: return@map null
                val sampleRows = csvImportRepository.getImportRows(listedImport.id, limit = STRATEGY_CONTENT_SAMPLE_SIZE, offset = 0)
                fullImport.lastAppliedStrategyId?.let { id -> strategies.find { it.id == id } }
                    ?: strategies.selectForCsv(fullImport.originalFileName, fullImport.columns, sampleRows)
            }
    }
    val matches = matchedStrategies
    val skippedNoStrategyCount = if (matches == null) 0 else imported.size - matches.count { it != null }
    val needsSourceAccount = matches?.any { it != null && it.needsSourceAccountOverride() } ?: false

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
                    if (needsSourceAccount) {
                        AccountPicker(
                            selectedAccountId = sourceAccountId,
                            onAccountSelected = { sourceAccountId = it },
                            label = "Source account (for files whose strategy needs one)",
                            accountRepository = accountRepository,
                            categoryRepository = categoryRepository,
                            personRepository = personRepository,
                            enabled = !isRunning,
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
                        if (needsSourceAccount && sourceAccountId == null) return@LoadingTextButton
                        isRunning = true
                        scope.launch {
                            try {
                                result =
                                    bulkReimportCsv(
                                        imports = imported,
                                        sourceAccountOverride = sourceAccountId,
                                        strategies = strategies,
                                        currencies = currencies,
                                        accountMappingRepository = accountMappingRepository,
                                        accountRepository = accountRepository,
                                        csvImportRepository = csvImportRepository,
                                        transactionRepository = transactionRepository,
                                        relationshipRepository = transferRelationshipRepository,
                                        transferSourceRepository = transferSourceRepository,
                                        maintenance = maintenance,
                                        importEngine = importEngine,
                                        onProgress = { done, total -> progress = done to total },
                                        passThroughAccounts = passThroughAccounts,
                                    )
                            } finally {
                                isRunning = false
                            }
                        }
                    },
                    enabled = !isRunning && (!needsSourceAccount || sourceAccountId != null),
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
