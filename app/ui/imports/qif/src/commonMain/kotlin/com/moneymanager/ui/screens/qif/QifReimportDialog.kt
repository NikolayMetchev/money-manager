@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens.qif

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import com.moneymanager.csvimporter.CsvReimportResult
import com.moneymanager.csvimporter.ReimportPlan
import com.moneymanager.csvimporter.needsSourceAccountOverride
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.model.qif.QifImportRecord
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.QifImportReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportProgress
import com.moneymanager.qifimporter.QifCsvAdapter
import com.moneymanager.qifimporter.executeQifReimport
import com.moneymanager.qifimporter.planQifReimport
import com.moneymanager.qifimporter.qifCompatible
import com.moneymanager.qifimporter.selectForQifContent
import com.moneymanager.qifimporter.withQifCurrency
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.CurrencyPicker
import com.moneymanager.ui.components.LoadingTextButton
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

private const val VALUE_UPDATE_PREVIEW_LIMIT = 20

/**
 * Re-imports an already-imported QIF so strategy/mapping changes take effect retroactively: shows a
 * read-only preview of the duplicate-account merges the current mappings imply and the transactions
 * whose values the current strategy now computes differently, then (on confirm) merges/updates them,
 * re-runs the strategy over never-imported/errored records, and deletes import-created accounts left
 * empty. Mirrors the CSV ReimportDialog minus the pass-through-rewrite path (QIF has no pass-through).
 * QIF carries no currency, so the one used at import time is chosen here (default preselected) — pick the
 * same one to avoid spurious value updates.
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun QifReimportDialog(
    qifImport: QifImport,
    records: List<QifImportRecord>,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyReadRepository,
    personRepository: PersonReadRepository,
    qifImportRepository: QifImportReadRepository,
    transactionRepository: TransactionReadRepository,
    transferSourceRepository: TransferSourceReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val currencies by currencyRepository
        .getAllCurrencies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val strategies by csvImportStrategyRepository
        .getAllStrategies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    val rows = remember(records) { QifCsvAdapter.toRows(records) }

    var baseStrategy by remember { mutableStateOf<CsvImportStrategy?>(null) }
    var strategyResolved by remember { mutableStateOf(false) }
    var selectedSourceAccountId by remember { mutableStateOf<AccountId?>(null) }
    var selectedCurrencyId by remember { mutableStateOf<CurrencyId?>(null) }
    var plan by remember { mutableStateOf<ReimportPlan?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var planProgress by remember { mutableStateOf<ImportProgress?>(null) }
    var executeProgress by remember { mutableStateOf<ImportProgress?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }

    // Resolve the strategy that was last applied; fall back to content-aware auto-selection if it was
    // deleted. Only QIF-compatible strategies are considered.
    LaunchedEffect(qifImport.id, strategies) {
        if (strategies.isEmpty()) return@LaunchedEffect
        val qifStrategies = strategies.qifCompatible()
        baseStrategy =
            qifImport.lastAppliedStrategyId
                ?.let { id -> qifStrategies.find { it.id == id } }
                ?: qifStrategies.selectForQifContent(rows, QifCsvAdapter.columns)
        strategyResolved = true
    }

    // Preselect the DB default currency (the import path's default), and a hard-coded strategy source.
    LaunchedEffect(currencies) {
        if (selectedCurrencyId == null && currencies.isNotEmpty()) {
            selectedCurrencyId = currencies.firstOrNull()?.id
        }
    }

    val currencyStrategy = baseStrategy?.withQifCurrency(selectedCurrencyId)
    val sourceMapping = currencyStrategy?.fieldMappings?.get(TransferField.SOURCE_ACCOUNT)
    val hasHardcodedSource = sourceMapping is HardCodedAccountMapping
    val hasPendingRecords = records.any { it.supported && (it.importStatus == null || it.importStatus == ImportStatus.ERROR) }
    val needsSourcePicker = currencyStrategy?.needsSourceAccountOverride() == true && hasPendingRecords && !hasHardcodedSource
    val sourceReady = !needsSourcePicker || selectedSourceAccountId != null

    // Build the read-only preview once the inputs are ready.
    LaunchedEffect(currencyStrategy, selectedSourceAccountId, currencies) {
        val strategy = currencyStrategy ?: return@LaunchedEffect
        if (currencies.isEmpty() || selectedCurrencyId == null) return@LaunchedEffect
        try {
            plan =
                planQifReimport(
                    qifImport = qifImport,
                    strategy = strategy,
                    sourceAccountOverride = selectedSourceAccountId,
                    currencies = currencies,
                    accountMappingRepository = accountMappingRepository,
                    accountRepository = accountRepository,
                    qifImportRepository = qifImportRepository,
                    transactionRepository = transactionRepository,
                    transferSourceRepository = transferSourceRepository,
                    onProgress = { planProgress = it },
                )
            errorMessage = null
        } catch (expected: CancellationException) {
            throw expected
        } catch (expected: Exception) {
            logger.error(expected) { "QIF re-import preview failed: ${expected.message}" }
            errorMessage = "Failed to prepare re-import: ${expected.message}"
            plan = null
        } finally {
            planProgress = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        title = { Text(if (summary != null) "Re-import complete" else "Re-import ${qifImport.originalFileName}") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
                val currentSummary = summary
                when {
                    currentSummary != null -> Text(currentSummary, style = MaterialTheme.typography.bodyMedium)
                    !strategyResolved -> CircularProgressIndicator()
                    baseStrategy == null ->
                        Text(
                            text = "No QIF strategy matches this import. Create or restore a matching strategy first.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    else -> {
                        Text(
                            text = "Strategy: ${baseStrategy?.name}",
                            style = MaterialTheme.typography.bodyMedium,
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

                        if (needsSourcePicker) {
                            AccountPicker(
                                selectedAccountId = selectedSourceAccountId,
                                onAccountSelected = { selectedSourceAccountId = it },
                                label = "Source Account (for not-yet-imported records)",
                                accountRepository = accountRepository,
                                categoryRepository = categoryRepository,
                                personRepository = personRepository,
                                enabled = !isRunning,
                                isError = selectedSourceAccountId == null,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        when (val currentPlan = plan) {
                            null ->
                                if (errorMessage == null) {
                                    QifReimportProgressIndicator(planProgress ?: ImportProgress("Preparing preview"))
                                }
                            else -> {
                                if (isRunning) {
                                    QifReimportProgressIndicator(executeProgress ?: ImportProgress("Starting re-import"))
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                                QifReimportPlanPreview(currentPlan, hasPendingRecords)
                            }
                        }
                    }
                }

                errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (summary != null) {
                TextButton(onClick = onComplete) { Text("Done") }
            } else {
                LoadingTextButton(
                    onClick = {
                        val strategy = currencyStrategy ?: return@LoadingTextButton
                        val currentPlan = plan ?: return@LoadingTextButton
                        isRunning = true
                        errorMessage = null
                        scope.launch {
                            try {
                                val result =
                                    executeQifReimport(
                                        plan = currentPlan,
                                        qifImport = qifImport,
                                        strategy = strategy,
                                        sourceAccountOverride = selectedSourceAccountId,
                                        currencies = currencies,
                                        accountMappingRepository = accountMappingRepository,
                                        accountRepository = accountRepository,
                                        qifImportRepository = qifImportRepository,
                                        maintenance = maintenance,
                                        importEngine = importEngine,
                                        onProgress = { executeProgress = it },
                                    )
                                summary = result.toDisplaySummary()
                            } catch (expected: CancellationException) {
                                throw expected
                            } catch (expected: Exception) {
                                logger.error(expected) { "QIF re-import failed: ${expected.message}" }
                                errorMessage = "Re-import failed: ${expected.message}"
                                isRunning = false
                            } finally {
                                executeProgress = null
                            }
                        }
                    },
                    enabled = !isRunning && baseStrategy != null && plan != null && selectedCurrencyId != null && sourceReady,
                    loading = isRunning,
                    label = "Re-import",
                    loadingIndicatorModifier = Modifier.padding(end = 8.dp),
                    showLabelWhenLoading = true,
                )
            }
        },
        dismissButton = {
            if (summary == null) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isRunning,
                ) {
                    Text("Cancel")
                }
            }
        },
    )
}

/** One-line outcome summary for the completed re-import. */
private fun CsvReimportResult.toDisplaySummary(): String =
    buildString {
        val imported = importResult?.successCount ?: 0
        val duplicates = importResult?.duplicateCount ?: 0
        append("Re-import complete")
        append(" · $imported new")
        if (updatedRows.isNotEmpty()) append(" · ${updatedRows.size} updated")
        if (mergedAccounts.isNotEmpty()) append(" · ${mergedAccounts.size} account${if (mergedAccounts.size == 1) "" else "s"} merged")
        if (deletedEmptyAccounts.isNotEmpty()) append(" · ${deletedEmptyAccounts.size} empty removed")
        if (duplicates > 0) append(" · $duplicates duplicates skipped")
        if (skipped.isNotEmpty()) append(" · ${skipped.size} not merged/updated")
    }

@Composable
private fun QifReimportProgressIndicator(progress: ImportProgress) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val counts =
            progress.processed
                ?.let { processed ->
                    progress.total?.let { total -> " — $processed of $total" }
                }.orEmpty()
        Text(
            text = "${progress.detail}$counts…",
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
    }
}

@Composable
private fun QifReimportPlanPreview(
    plan: ReimportPlan,
    hasPendingRecords: Boolean,
) {
    Column {
        if (plan.merges.isNotEmpty()) {
            Text(
                text = "Duplicate accounts to merge:",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            plan.merges.forEach { merge ->
                Text(
                    text = "• ${merge.duplicateName} → ${merge.targetName} (${merge.transferCount} transaction(s))",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text =
                    "Merges move ALL of the duplicate's transactions (from any import) and can be undone " +
                        "from the account merge history.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "No duplicate accounts to merge under the current account mappings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (plan.reversals.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Merges to reverse (accounts to split back out):",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            plan.reversals.forEach { reversal ->
                Text(
                    text = "• ${reversal.deletedAccountName} ← ${reversal.survivingName} (${reversal.transferCount} transaction(s))",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text =
                    "The current mappings no longer consolidate these onto the survivor, so the earlier merge " +
                        "is undone — the account is recreated and its transactions move back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (plan.valueUpdates.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Transactions to update to the strategy's current values (${plan.valueUpdates.size}):",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            plan.valueUpdates.take(VALUE_UPDATE_PREVIEW_LIMIT).forEach { update ->
                Text(
                    text = "• ${update.description}: ${update.changes.joinToString("; ")}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (plan.valueUpdates.size > VALUE_UPDATE_PREVIEW_LIMIT) {
                Text(
                    text = "…and ${plan.valueUpdates.size - VALUE_UPDATE_PREVIEW_LIMIT} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text =
                    "Each transaction is updated in place — including over any manual edits made to " +
                        "its amount, date or description. Split transactions are not value-updated.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (plan.skipped.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Not merged:",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(4.dp))
            plan.skipped.forEach { skip ->
                Text(
                    text = "• ${skip.accountName}: ${skip.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (hasPendingRecords) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Records not yet imported (or in error) will also be imported using the current mappings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Accounts created by this import that end up with no transactions will be deleted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
