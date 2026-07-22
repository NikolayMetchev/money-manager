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
import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.BulkImportProgress
import com.moneymanager.csvimporter.CsvBulkReimportResult
import com.moneymanager.csvimporter.STRATEGY_CONTENT_SAMPLE_SIZE
import com.moneymanager.csvimporter.bulkReimportCsv
import com.moneymanager.csvimporter.selectForCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.ui.components.LoadingTextButton
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-imports every already-imported CSV file in one go so current strategy/mapping changes apply
 * retroactively (duplicate accounts merged, changed transfer values updated, never-imported/errored
 * rows imported, emptied import-created accounts removed). Each file's strategy is the one it was last
 * imported with, falling back to content-aware auto-selection (files with no match are skipped). A
 * file whose strategy needs a source account (no SOURCE_ACCOUNT mapping) always resolves it from its
 * own import history ([CsvImportReadRepository.historicalSourceAccounts]) rather than a shared,
 * dialog-level pick — different files can (and do) belong to different accounts, so one shared choice
 * across the whole batch never made sense here. Single confirm + aggregated summary — no per-file
 * preview. Mirrors [CsvImportAllDialog] by design.
 */
@Composable
@Suppress("LongParameterList", "LongMethod", "DuplicatedCode")
fun CsvReimportAllDialog(
    imported: List<CsvImport>,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    currencyRepository: CurrencyReadRepository,
    cryptoRepository: CryptoReadRepository,
    passThroughAccountRepository: PassThroughAccountReadRepository,
    csvImportRepository: CsvImportReadRepository,
    transactionRepository: TransactionReadRepository,
    transferRelationshipRepository: TransferRelationshipReadRepository,
    transferSourceRepository: TransferSourceReadRepository,
    tradeRepository: TradeReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val strategies by csvImportStrategyRepository.getAllStrategies().collectAsStateWithSchemaErrorHandling(emptyList())
    val currencies by currencyRepository.getAllCurrencies().collectAsStateWithSchemaErrorHandling(emptyList())
    val passThroughAccounts by passThroughAccountRepository.getAll().collectAsStateWithSchemaErrorHandling(emptyList())
    val accountAttributes by accountAttributeRepository
        .getAll()
        .collectAsStateWithSchemaErrorHandling(emptyList())

    var isRunning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<BulkImportProgress?>(null) }
    var result by remember { mutableStateOf<CsvBulkReimportResult?>(null) }

    // Resolve each file's strategy the same way the re-import will: the one it was last imported with,
    // falling back to content-aware auto-selection. Lets us report how many files will be skipped (no
    // matching strategy). getAllImports() omits columns, so re-fetch.
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
            if (result != null) {
                TextButton(onClick = onComplete) { Text("Done") }
            } else {
                LoadingTextButton(
                    onClick = {
                        isRunning = true
                        scope.launch {
                            try {
                                result =
                                    bulkReimportCsv(
                                        imports = imported,
                                        sourceAccountOverride = null,
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
                                        onProgress = { progress = it },
                                        passThroughAccounts = passThroughAccounts,
                                        cryptoRepository = cryptoRepository,
                                        tradeRepository = tradeRepository,
                                        attributeAccountMatchers = AttributeAccountMatcher.registry(accountAttributes),
                                    )
                            } finally {
                                isRunning = false
                            }
                        }
                    },
                    enabled = !isRunning && matches != null,
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
