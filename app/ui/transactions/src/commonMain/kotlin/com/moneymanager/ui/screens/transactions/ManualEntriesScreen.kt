package com.moneymanager.ui.screens.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.PendingManualEntry
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ManualEntryInput
import com.moneymanager.importengineapi.ManualTradeInput
import com.moneymanager.importengineapi.createManualEntries
import com.moneymanager.importengineapi.createManualTradeEntries
import com.moneymanager.presenter.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.components.CurrencyPicker
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.util.displayDateTime
import com.moneymanager.ui.util.formatAmount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Transfers one strategy's manual-entry rule asked about, still waiting for the amount.
 */
private data class PendingEntryGroup(
    /** Unique per strategy and action, so two actions never share a list key even when they name the same transfers. */
    val key: String,
    val strategyName: String,
    val entry: RuleAction.ManualEntry,
    val pending: List<PendingManualEntry>,
)

/**
 * Lists the imported transfers a `ManualEntry` rule action asked a person about (a Wise accrual charge's interest,
 * a Binance withdrawal's included fee) and creates the entries they type: one transfer each, linked to its original.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun ManualEntriesScreen(
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    transactionRepository: TransactionReadRepository,
    tradeRepository: TradeReadRepository,
    currencyRepository: CurrencyReadRepository,
    maintenance: Maintenance,
    onTransactionsImported: () -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val importEngine = LocalImportEngine.current
    val groupsFlow =
        remember(csvImportStrategyRepository, transactionRepository) {
            csvImportStrategyRepository.getAllStrategies().flatMapLatest { strategies ->
                val ruleFlows =
                    strategies.flatMap { strategy ->
                        strategy.config.rules
                            .flatMap { it.actions }
                            .filterIsInstance<RuleAction.ManualEntry>()
                            .distinct()
                            .mapIndexed { index, entry ->
                                transactionRepository
                                    .getPendingManualEntries(entry.name, entry.relationshipType)
                                    .map { pending ->
                                        PendingEntryGroup(
                                            "${strategy.id.id}/$index/${entry.name}/${entry.relationshipType}",
                                            strategy.name,
                                            entry,
                                            pending,
                                        )
                                    }
                            }
                    }
                // combine() over an empty list never emits, so short-circuit instead.
                if (ruleFlows.isEmpty()) flowOf(emptyList()) else combine(ruleFlows) { it.toList() }
            }
        }
    val groups by groupsFlow.collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val amounts = remember { mutableStateMapOf<TransferId, String>() }
    val pendingTradesFlow =
        remember(csvImportStrategyRepository, tradeRepository) {
            csvImportStrategyRepository.getAllStrategies().flatMapLatest { strategies ->
                val codes =
                    strategies
                        .flatMap { strategy -> strategy.config.legGroups.mapNotNull { (it.assembly as? LegAssembly.Trade)?.unpairedAsset } }
                        .distinct()
                val flows =
                    codes.map { code ->
                        tradeRepository
                            .getPendingManualTrades(WellKnownIds.MANUAL_ENTRY_ATTR_TYPE_NAME, code)
                            .map { trades -> trades.map { PendingTradeEntry(it, code) } }
                    }
                if (flows.isEmpty()) flowOf(emptyList()) else combine(flows) { it.toList().flatten() }
            }
        }
    val pendingTrades by pendingTradesFlow.collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val currencies by currencyRepository.getAllCurrencies().collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val tradeCurrencies = remember { mutableStateMapOf<TradeId, CurrencyId>() }
    val tradeAmounts = remember { mutableStateMapOf<TradeId, String>() }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val visibleGroups = groups.filter { it.pending.isNotEmpty() }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        Text(
            text = "Manual Entries",
            style = MaterialTheme.typography.headlineMedium,
        )

        errorMessage?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // The saved entries drop out of the list before the save finishes, so "nothing pending" waits for it.
        if (visibleGroups.isEmpty() && pendingTrades.isEmpty() && !isSaving) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No pending manual entries.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                visibleGroups.forEach { group ->
                    item(key = group.key) {
                        PendingGroupHeader(
                            group = group,
                            amounts = amounts,
                            isSaving = isSaving,
                            onCreate = { entries ->
                                isSaving = true
                                errorMessage = null
                                scope.launch {
                                    try {
                                        createEntries(
                                            entry = group.entry,
                                            entries = entries,
                                            importEngine = importEngine,
                                        )
                                        maintenance.refreshMaterializedViews()
                                        entries.forEach { (matched, _) -> amounts.remove(matched.transferId) }
                                        onTransactionsImported()
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (expected: Exception) {
                                        errorMessage = "Failed to create transactions: ${expected.message}"
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            },
                        )
                    }
                    items(group.pending, key = { "${group.key}/${it.transferId.id}" }) { matched ->
                        PendingEntryCard(
                            matched = matched,
                            entry = group.entry,
                            amountText = amounts[matched.transferId].orEmpty(),
                            onAmountChange = { amounts[matched.transferId] = it },
                        )
                    }
                }
                if (pendingTrades.isNotEmpty()) {
                    item(key = "pending-trades") {
                        val answers =
                            pendingTrades.mapNotNull { entry ->
                                val currency = currencies.firstOrNull { it.id == tradeCurrencies[entry.trade.id] } ?: return@mapNotNull null
                                val amount = parseAmount(tradeAmounts[entry.trade.id].orEmpty()) ?: return@mapNotNull null
                                ManualTradeInput(entry.trade, entry.placeholderAssetCode, Money.fromDisplayValue(amount, currency))
                            }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = "Exchanges with no named currency", style = MaterialTheme.typography.titleLarge)
                            Button(
                                onClick = {
                                    isSaving = true
                                    errorMessage = null
                                    scope.launch {
                                        try {
                                            importEngine.createManualTradeEntries(answers)
                                            maintenance.refreshMaterializedViews()
                                            answers.forEach {
                                                tradeAmounts.remove(it.original.id)
                                                tradeCurrencies.remove(it.original.id)
                                            }
                                            onTransactionsImported()
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (expected: Exception) {
                                            errorMessage = "Failed to create trades: ${expected.message}"
                                        } finally {
                                            isSaving = false
                                        }
                                    }
                                },
                                enabled = answers.isNotEmpty() && !isSaving,
                            ) {
                                Text("Create ${answers.size} trade${if (answers.size == 1) "" else "s"}")
                            }
                        }
                    }
                    items(pendingTrades, key = { "trade/${it.trade.id.id}" }) { entry ->
                        PendingTradeCard(
                            entry = entry,
                            currencyRepository = currencyRepository,
                            currencyId = tradeCurrencies[entry.trade.id],
                            onCurrencyChange = { tradeCurrencies[entry.trade.id] = it },
                            amountText = tradeAmounts[entry.trade.id].orEmpty(),
                            onAmountChange = { tradeAmounts[entry.trade.id] = it },
                        )
                    }
                }
            }
        }
    }
}

private suspend fun createEntries(
    entry: RuleAction.ManualEntry,
    entries: List<Pair<PendingManualEntry, BigDecimal>>,
    importEngine: ImportEngine,
) = importEngine.createManualEntries(
    inputs = entries.map { (original, amount) -> ManualEntryInput(original, Money.fromDisplayValue(amount, original.amount.asset)) },
    description = entry.description,
    relationshipType = entry.relationshipType,
    excludedFromBalances = entry.excludedFromBalances,
)

private fun parseAmount(text: String): BigDecimal? {
    if (text.isBlank()) return null
    val parsed = runCatching { BigDecimal(text.trim()) }.getOrNull() ?: return null
    return parsed.takeIf { it > BigDecimal.ZERO }
}

@Composable
private fun PendingGroupHeader(
    group: PendingEntryGroup,
    amounts: Map<TransferId, String>,
    isSaving: Boolean,
    onCreate: (List<Pair<PendingManualEntry, BigDecimal>>) -> Unit,
) {
    val entries =
        group.pending.mapNotNull { matched ->
            val text = amounts[matched.transferId].orEmpty()
            if (text.isBlank()) return@mapNotNull null
            parseAmount(text)?.let { matched to it }
        }
    val hasInvalidInput =
        group.pending.any { matched ->
            val text = amounts[matched.transferId].orEmpty()
            text.isNotBlank() && parseAmount(text) == null
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${group.strategyName} — ${group.entry.name}",
            style = MaterialTheme.typography.titleLarge,
        )
        Button(
            onClick = { onCreate(entries) },
            enabled = entries.isNotEmpty() && !hasInvalidInput && !isSaving,
        ) {
            Text("Create ${entries.size} transaction${if (entries.size == 1) "" else "s"}")
        }
    }
}

@Composable
private fun PendingEntryCard(
    matched: PendingManualEntry,
    entry: RuleAction.ManualEntry,
    amountText: String,
    onAmountChange: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = matched.description,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text =
                        "${matched.timestamp.displayDateTime()} • " +
                            "${matched.sourceAccountName} → ${matched.targetAccountName} • " +
                            formatAmount(matched.amount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text =
                        if (entry.relationshipType == WellKnownIds.FEE_RELATIONSHIP_TYPE_NAME) {
                            "Entry: ${matched.sourceAccountName} → its fee account"
                        } else {
                            "Entry: ${matched.targetAccountName} → ${matched.sourceAccountName}"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            OutlinedTextField(
                value = amountText,
                onValueChange = onAmountChange,
                label = { Text("Amount (${matched.amount.asset.code})") },
                isError = amountText.isNotBlank() && parseAmount(amountText) == null,
                singleLine = true,
                modifier = Modifier.width(180.dp),
            )
        }
    }
}

/** A placeholder trade waiting for the currency and amount of its [placeholderAssetCode] leg. */
private data class PendingTradeEntry(
    val trade: Trade,
    val placeholderAssetCode: String,
)

@Composable
private fun PendingTradeCard(
    entry: PendingTradeEntry,
    currencyRepository: CurrencyReadRepository,
    currencyId: CurrencyId?,
    onCurrencyChange: (CurrencyId) -> Unit,
    amountText: String,
    onAmountChange: (String) -> Unit,
) {
    val trade = entry.trade
    val placeholderIsTo = trade.to.asset.code == entry.placeholderAssetCode
    val known = if (placeholderIsTo) trade.from else trade.to
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = trade.description, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text =
                        "${trade.timestamp.displayDateTime()} • " +
                            (if (placeholderIsTo) "gave ${formatAmount(known)} for " else "received ${formatAmount(known)} for ") +
                            "an unnamed currency",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CurrencyPicker(
                selectedCurrencyId = currencyId,
                onCurrencySelected = onCurrencyChange,
                label = "Currency",
                currencyRepository = currencyRepository,
                modifier = Modifier.width(180.dp),
            )
            OutlinedTextField(
                value = amountText,
                onValueChange = onAmountChange,
                label = { Text("Amount") },
                isError = amountText.isNotBlank() && parseAmount(amountText) == null,
                singleLine = true,
                modifier = Modifier.width(160.dp),
            )
        }
    }
}
