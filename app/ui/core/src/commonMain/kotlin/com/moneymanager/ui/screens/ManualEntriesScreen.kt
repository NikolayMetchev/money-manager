@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

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
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.TransferMissingCompanion
import com.moneymanager.domain.model.csvstrategy.CompanionTransactionRule
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.util.displayDateTime
import com.moneymanager.ui.util.formatAmount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Transfers matched by one strategy's companion rule that still need their companion entered.
 */
private data class PendingCompanionGroup(
    val strategyName: String,
    val rule: CompanionTransactionRule,
    val pending: List<TransferMissingCompanion>,
)

/**
 * Lists imported transfers flagged by companion transaction rules as requiring a manually
 * entered companion (e.g. Wise assets fees needing their interest payment) and bulk-creates
 * the companions: mirrored accounts, same timestamp and currency, user-entered amount.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun ManualEntriesScreen(
    csvImportStrategyRepository: CsvImportStrategyRepository,
    transactionRepository: TransactionRepository,
    attributeTypeRepository: AttributeTypeRepository,
    maintenance: Maintenance,
    onTransactionsImported: () -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val groupsFlow =
        remember(csvImportStrategyRepository, transactionRepository) {
            csvImportStrategyRepository.getAllStrategies().flatMapLatest { strategies ->
                val ruleFlows =
                    strategies.flatMap { strategy ->
                        strategy.companionTransactionRules.map { rule ->
                            transactionRepository
                                .getTransfersMissingCompanion(
                                    matchAttributeName = rule.matchAttributeName,
                                    matchValuePattern = rule.matchValuePattern,
                                    linkAttributeName = rule.linkAttributeName,
                                ).map { pending -> PendingCompanionGroup(strategy.name, rule, pending) }
                        }
                    }
                // combine() over an empty list never emits, so short-circuit instead.
                if (ruleFlows.isEmpty()) flowOf(emptyList()) else combine(ruleFlows) { it.toList() }
            }
        }
    val groups by groupsFlow.collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val amounts = remember { mutableStateMapOf<TransferId, String>() }
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

        if (visibleGroups.isEmpty()) {
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
                    item(key = "${group.strategyName}/${group.rule.name}") {
                        PendingGroupHeader(
                            group = group,
                            amounts = amounts,
                            isSaving = isSaving,
                            onCreate = { entries ->
                                isSaving = true
                                errorMessage = null
                                scope.launch {
                                    try {
                                        createCompanionTransfers(
                                            rule = group.rule,
                                            entries = entries,
                                            transactionRepository = transactionRepository,
                                            attributeTypeRepository = attributeTypeRepository,
                                        )
                                        maintenance.refreshMaterializedViews()
                                        entries.forEach { (matched, _) -> amounts.remove(matched.transferId) }
                                        onTransactionsImported()
                                    } catch (expected: Exception) {
                                        errorMessage = "Failed to create transactions: ${expected.message}"
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            },
                        )
                    }
                    items(group.pending, key = { it.transferId.id }) { matched ->
                        PendingCompanionCard(
                            matched = matched,
                            amountText = amounts[matched.transferId].orEmpty(),
                            onAmountChange = { amounts[matched.transferId] = it },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Creates one mirror transfer per entry, each linked to its matched transfer via the
 * rule's link attribute so the pair is no longer reported as missing.
 */
private suspend fun createCompanionTransfers(
    rule: CompanionTransactionRule,
    entries: List<Pair<TransferMissingCompanion, BigDecimal>>,
    transactionRepository: TransactionRepository,
    attributeTypeRepository: AttributeTypeRepository,
) {
    val linkTypeId = attributeTypeRepository.getOrCreate(rule.linkAttributeName)
    val transfers =
        entries.mapIndexed { index, (matched, amount) ->
            Transfer(
                // Distinct placeholder ids: createTransfers keys newAttributes by them.
                id = TransferId(-(index + 1L)),
                timestamp = matched.timestamp,
                description = rule.companionDescription,
                sourceAccountId = matched.targetAccountId,
                targetAccountId = matched.sourceAccountId,
                amount = Money.fromDisplayValue(amount, matched.amount.currency),
            )
        }
    val newAttributes =
        transfers
            .mapIndexed { index, transfer ->
                transfer.id to listOf(NewAttribute(linkTypeId, entries[index].first.matchValue))
            }.toMap()
    transactionRepository.createTransfers(
        transfers = transfers,
        newAttributes = newAttributes,
        sources = List(transfers.size) { Source.Manual },
    )
}

private fun parseAmount(text: String): BigDecimal? {
    if (text.isBlank()) return null
    val parsed = runCatching { BigDecimal(text.trim()) }.getOrNull() ?: return null
    return parsed.takeIf { it > BigDecimal.ZERO }
}

@Composable
private fun PendingGroupHeader(
    group: PendingCompanionGroup,
    amounts: Map<TransferId, String>,
    isSaving: Boolean,
    onCreate: (List<Pair<TransferMissingCompanion, BigDecimal>>) -> Unit,
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
            text = "${group.strategyName} — ${group.rule.name}",
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
private fun PendingCompanionCard(
    matched: TransferMissingCompanion,
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
                    text = "Companion: ${matched.targetAccountName} → ${matched.sourceAccountName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            OutlinedTextField(
                value = amountText,
                onValueChange = onAmountChange,
                label = { Text("Amount (${matched.amount.currency.code})") },
                isError = amountText.isNotBlank() && parseAmount(amountText) == null,
                singleLine = true,
                modifier = Modifier.width(180.dp),
            )
        }
    }
}
