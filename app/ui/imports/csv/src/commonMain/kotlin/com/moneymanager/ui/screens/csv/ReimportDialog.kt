package com.moneymanager.ui.screens.csv

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
import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.CsvReimportResult
import com.moneymanager.csvimporter.ReimportPlan
import com.moneymanager.csvimporter.executeCsvReimport
import com.moneymanager.csvimporter.needsSourceAccountOverride
import com.moneymanager.csvimporter.planCsvReimport
import com.moneymanager.csvimporter.selectForCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportProgress
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.LoadingTextButton
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

private const val VALUE_UPDATE_PREVIEW_LIMIT = 20

/**
 * Re-imports an already-imported CSV so strategy/mapping changes take effect retroactively: shows a
 * read-only preview of the duplicate-account merges the current mappings imply and the transactions
 * whose values the current strategy now computes differently, then (on confirm) merges/updates them,
 * re-runs the strategy over never-imported/errored rows, and deletes import-created accounts left
 * empty.
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun ReimportDialog(
    csvImport: CsvImport,
    rows: List<CsvRow>,
    csvImportRepository: CsvImportReadRepository,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyReadRepository,
    personRepository: PersonReadRepository,
    passThroughAccountRepository: PassThroughAccountReadRepository,
    transactionRepository: TransactionReadRepository,
    transferRelationshipRepository: TransferRelationshipReadRepository,
    transferSourceRepository: TransferSourceReadRepository,
    cryptoRepository: CryptoReadRepository,
    tradeRepository: TradeReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onDismiss: () -> Unit,
    onComplete: (CsvReimportResult) -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val currencies by currencyRepository
        .getAllCurrencies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val passThroughAccounts by passThroughAccountRepository
        .getAll()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val accountAttributes by accountAttributeRepository
        .getAll()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    var strategy by remember { mutableStateOf<CsvImportStrategy?>(null) }
    var strategyResolved by remember { mutableStateOf(false) }
    var selectedSourceAccountId by remember { mutableStateOf<AccountId?>(null) }
    var plan by remember { mutableStateOf<ReimportPlan?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var planProgress by remember { mutableStateOf<ImportProgress?>(null) }
    var executeProgress by remember { mutableStateOf<ImportProgress?>(null) }

    // Resolve the strategy that was last applied; fall back to auto-selection if it was deleted.
    LaunchedEffect(csvImport.id) {
        val byId =
            csvImport.lastAppliedStrategyId?.let { strategyId ->
                csvImportStrategyRepository.getStrategyById(strategyId).first()
            }
        strategy = byId
            ?: csvImportStrategyRepository
                .getAllStrategies()
                .first()
                .selectForCsv(csvImport.originalFileName, csvImport.columns, rows)
        strategyResolved = true
    }

    // A source-account picker is only needed to import remaining rows; merges don't use it.
    val hasUnimportedRows = rows.any { it.importStatus == null || it.importStatus == ImportStatus.ERROR }
    val needsSourcePicker = strategy?.needsSourceAccountOverride() == true && hasUnimportedRows
    val sourceReady = !needsSourcePicker || selectedSourceAccountId != null

    // Build the read-only merge preview once the inputs are ready.
    LaunchedEffect(strategy, selectedSourceAccountId, currencies, passThroughAccounts, accountAttributes) {
        val currentStrategy = strategy ?: return@LaunchedEffect
        if (currencies.isEmpty()) return@LaunchedEffect
        try {
            plan =
                planCsvReimport(
                    csvImport = csvImport,
                    strategy = currentStrategy,
                    sourceAccountOverride = selectedSourceAccountId,
                    currencies = currencies,
                    accountMappingRepository = accountMappingRepository,
                    accountRepository = accountRepository,
                    csvImportRepository = csvImportRepository,
                    transactionRepository = transactionRepository,
                    relationshipRepository = transferRelationshipRepository,
                    transferSourceRepository = transferSourceRepository,
                    passThroughAccounts = passThroughAccounts,
                    // Crypto tickers on already-imported rows must resolve for the value-update and
                    // transfer→trade conversion scans, so pass the full asset set.
                    cryptoAssets = cryptoRepository.getAllCryptoAssets().first(),
                    attributeAccountMatchers = AttributeAccountMatcher.registry(accountAttributes),
                    onProgress = { planProgress = it },
                )
            errorMessage = null
        } catch (expected: CancellationException) {
            throw expected
        } catch (expected: Exception) {
            logger.error(expected) { "Re-import preview failed: ${expected.message}" }
            errorMessage = "Failed to prepare re-import: ${expected.message}"
            plan = null
        } finally {
            planProgress = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        title = { Text("Re-import ${csvImport.originalFileName}") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
                when {
                    !strategyResolved -> CircularProgressIndicator()
                    strategy == null ->
                        Text(
                            text = "No strategy matches this import. Create or restore a matching strategy first.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    else -> {
                        Text(
                            text = "Strategy: ${strategy?.name}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        if (needsSourcePicker) {
                            AccountPicker(
                                selectedAccountId = selectedSourceAccountId,
                                onAccountSelected = { selectedSourceAccountId = it },
                                label = "Source Account (for not-yet-imported rows)",
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
                                    ReimportProgressIndicator(planProgress ?: ImportProgress("Preparing preview"))
                                }
                            else -> {
                                if (isRunning) {
                                    ReimportProgressIndicator(executeProgress ?: ImportProgress("Starting re-import"))
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                                ReimportPlanPreview(currentPlan, hasUnimportedRows)
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
            LoadingTextButton(
                onClick = {
                    val currentStrategy = strategy ?: return@LoadingTextButton
                    val currentPlan = plan ?: return@LoadingTextButton
                    isRunning = true
                    errorMessage = null
                    scope.launch {
                        try {
                            val result =
                                executeCsvReimport(
                                    plan = currentPlan,
                                    csvImport = csvImport,
                                    strategy = currentStrategy,
                                    sourceAccountOverride = selectedSourceAccountId,
                                    currencies = currencies,
                                    accountMappingRepository = accountMappingRepository,
                                    accountRepository = accountRepository,
                                    csvImportRepository = csvImportRepository,
                                    maintenance = maintenance,
                                    importEngine = importEngine,
                                    passThroughAccounts = passThroughAccounts,
                                    onProgress = { executeProgress = it },
                                    cryptoRepository = cryptoRepository,
                                    tradeRepository = tradeRepository,
                                    attributeAccountMatchers = AttributeAccountMatcher.registry(accountAttributes),
                                )
                            onComplete(result)
                        } catch (expected: CancellationException) {
                            throw expected
                        } catch (expected: Exception) {
                            logger.error(expected) { "Re-import failed: ${expected.message}" }
                            errorMessage = "Re-import failed: ${expected.message}"
                            isRunning = false
                        } finally {
                            executeProgress = null
                        }
                    }
                },
                enabled = !isRunning && strategy != null && plan != null && sourceReady,
                loading = isRunning,
                label = "Re-import",
                loadingIndicatorModifier = Modifier.padding(end = 8.dp),
                showLabelWhenLoading = true,
            )
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isRunning,
            ) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Phase label (plus "x of y" when counts are known) over a linear progress bar — determinate when
 * the phase reports a fraction, indeterminate otherwise.
 */
@Composable
private fun ReimportProgressIndicator(progress: ImportProgress) {
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
private fun ReimportPlanPreview(
    plan: ReimportPlan,
    hasUnimportedRows: Boolean,
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
                        "its amount, date or description.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (plan.rewrites.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Rows to reroute through pass-through accounts:",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            plan.rewrites.forEach { rewrite ->
                Text(
                    text = "• ${rewrite.description} → ${(rewrite.conduitNames + rewrite.merchantName).joinToString(" → ")}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text =
                    "Each row's old transaction(s) are deleted — including any manual edits made to " +
                        "them — and the row is re-imported through the conduit chain.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (plan.tradeConversions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Transfers to convert to trades (${plan.tradeConversions.size}):",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            plan.tradeConversions.take(VALUE_UPDATE_PREVIEW_LIMIT).forEach { conversion ->
                Text(
                    text = "• ${conversion.description}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (plan.tradeConversions.size > VALUE_UPDATE_PREVIEW_LIMIT) {
                Text(
                    text = "…and ${plan.tradeConversions.size - VALUE_UPDATE_PREVIEW_LIMIT} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text =
                    "These rows exchange one asset for another: each row's old single-asset transaction(s) " +
                        "are deleted — including any manual edits made to them — and the row is re-imported " +
                        "as a trade.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (plan.fundingReconciles.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Conduit spends to reconcile against their funding card (${plan.fundingReconciles.size}):",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            plan.fundingReconciles.take(VALUE_UPDATE_PREVIEW_LIMIT).forEach { reconcile ->
                Text(text = "• ${reconcile.description}", style = MaterialTheme.typography.bodySmall)
            }
            if (plan.fundingReconciles.size > VALUE_UPDATE_PREVIEW_LIMIT) {
                Text(
                    text = "…and ${plan.fundingReconciles.size - VALUE_UPDATE_PREVIEW_LIMIT} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text =
                    "These spends match a funding leg on the account holding their card number, so each is " +
                        "re-imported linked to that leg and excluded from balances (counted once) instead of " +
                        "double-counting the underlying card charge.",
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

        if (hasUnimportedRows) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Rows not yet imported (or in error) will also be imported using the current mappings.",
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
