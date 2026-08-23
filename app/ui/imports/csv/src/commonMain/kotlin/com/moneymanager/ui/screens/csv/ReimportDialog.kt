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
                    tradeRepository = tradeRepository,
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
                                CsvReimportPlanPreview(currentPlan, hasUnimportedRows)
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
 * The shared re-import preview plus the CSV-only sections: pass-through rewrites, transfer→trade
 * conversions and the cross-source reconciliations, which a QIF plan can never contain.
 */
@Composable
private fun CsvReimportPlanPreview(
    plan: ReimportPlan,
    hasUnimportedRows: Boolean,
) {
    ReimportPlanPreview(
        plan = plan,
        hasPendingItems = hasUnimportedRows,
        valueUpdateNote =
            "Each transaction is updated in place — including over any manual edits made to " +
                "its amount, date or description.",
        pendingItemsNote = "Rows not yet imported (or in error) will also be imported using the current mappings.",
    ) {
        if (plan.rewrites.isNotEmpty()) {
            ReimportPlanSection(
                title = "Rows to reroute through pass-through accounts:",
                lines =
                    plan.rewrites.map { rewrite ->
                        "${rewrite.description} → ${(rewrite.conduitNames + rewrite.merchantName).joinToString(" → ")}"
                    },
                note =
                    "Each row's old transaction(s) are deleted — including any manual edits made to " +
                        "them — and the row is re-imported through the conduit chain.",
            )
        }

        if (plan.tradeConversions.isNotEmpty()) {
            ReimportPlanSection(
                title = "Transfers to convert to trades (${plan.tradeConversions.size}):",
                lines = plan.tradeConversions.map { conversion -> conversion.description },
                note =
                    "These rows exchange one asset for another: each row's old single-asset transaction(s) " +
                        "are deleted — including any manual edits made to them — and the row is re-imported " +
                        "as a trade.",
                limit = REIMPORT_PREVIEW_LIMIT,
            )
        }

        if (plan.duplicateTrades.isNotEmpty()) {
            ReimportPlanSection(
                title = "Conversions another export already recorded (${plan.duplicateTrades.size}):",
                lines = plan.duplicateTrades.map { duplicate -> duplicate.description },
                note =
                    "These rows describe a conversion another export already imported under different " +
                        "wording (same instant, accounts, assets and amounts), so it was booked twice. The " +
                        "duplicate is deleted and the row re-linked to the surviving one.",
                limit = REIMPORT_PREVIEW_LIMIT,
            )
        }

        if (plan.staleDuplicates.isNotEmpty()) {
            ReimportPlanSection(
                title = "Rows sharing or missing a transaction (${plan.staleDuplicates.size}):",
                lines = plan.staleDuplicates.map { stale -> stale.description },
                note =
                    "These rows were collapsed onto a transaction another row also claims, or onto one that " +
                        "no longer exists, so the movement they record is missing from balances. They are " +
                        "released and re-imported so each gets its own transaction.",
                limit = REIMPORT_PREVIEW_LIMIT,
            )
        }

        if (plan.counterpartyReconciles.isNotEmpty()) {
            ReimportPlanSection(
                title = "Rows whose counterparty is unknown to this export (${plan.counterpartyReconciles.size}):",
                lines = plan.counterpartyReconciles.map { reconcile -> "${reconcile.description} — ${reconcile.detail}" },
                note =
                    "This export records that money moved but not whose account it came from or went to. " +
                        "Each row's transaction is deleted and re-imported against a placeholder counterparty, " +
                        "and where another source already recorded the same movement against a real account the " +
                        "row is linked to it and excluded from balances (counted once).",
                limit = REIMPORT_PREVIEW_LIMIT,
            )
        }

        if (plan.fundingReconciles.isNotEmpty()) {
            ReimportPlanSection(
                title = "Conduit spends to reconcile against their funding card (${plan.fundingReconciles.size}):",
                lines = plan.fundingReconciles.map { reconcile -> reconcile.description },
                note =
                    "These spends match a funding leg on the account holding their card number, so each is " +
                        "re-imported linked to that leg and excluded from balances (counted once) instead of " +
                        "double-counting the underlying card charge.",
                limit = REIMPORT_PREVIEW_LIMIT,
            )
        }
    }
}
