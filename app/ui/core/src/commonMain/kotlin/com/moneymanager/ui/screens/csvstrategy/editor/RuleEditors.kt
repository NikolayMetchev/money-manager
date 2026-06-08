package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csvstrategy.ColumnPairSwap
import com.moneymanager.domain.model.csvstrategy.CompanionTransactionRule
import com.moneymanager.domain.model.csvstrategy.RowPreprocessingRule

/**
 * Editor for the strategy's list of [RowPreprocessingRule]s.
 */
@Composable
internal fun RowPreprocessingRulesEditor(
    rules: List<RowPreprocessingRule>,
    onRulesChanged: (List<RowPreprocessingRule>) -> Unit,
    columns: List<CsvColumn>,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        rules.forEachIndexed { index, rule ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Rule ${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onRulesChanged(rules.filterIndexed { i, _ -> i != index }) },
                            enabled = enabled,
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove rule")
                        }
                    }

                    fun updateRule(transform: (RowPreprocessingRule) -> RowPreprocessingRule) {
                        onRulesChanged(rules.mapIndexed { i, r -> if (i == index) transform(r) else r })
                    }
                    RowConditionsEditor(
                        conditions = rule.conditions,
                        onConditionsChanged = { updated -> updateRule { it.copy(conditions = updated) } },
                        columns = columns,
                        enabled = enabled,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ColumnSwapsEditor(
                        swaps = rule.columnSwaps,
                        onSwapsChanged = { updated -> updateRule { it.copy(columnSwaps = updated) } },
                        columns = columns,
                        enabled = enabled,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = rule.flipSourceAndTarget,
                            onCheckedChange = { checked -> updateRule { it.copy(flipSourceAndTarget = checked) } },
                            enabled = enabled,
                        )
                        Text(
                            "Flip source and target accounts",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        TextButton(
            onClick = { onRulesChanged(rules + RowPreprocessingRule(conditions = emptyList())) },
            enabled = enabled,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text("Add Rule")
        }
    }
}

/**
 * Editor for a list of [ColumnPairSwap]s exchanged when a preprocessing rule applies.
 */
@Composable
internal fun ColumnSwapsEditor(
    swaps: List<ColumnPairSwap>,
    onSwapsChanged: (List<ColumnPairSwap>) -> Unit,
    columns: List<CsvColumn>,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Column swaps", style = MaterialTheme.typography.bodyMedium)
        swaps.forEachIndexed { index, swap ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    ColumnDropdown(
                        columns = columns,
                        selectedColumn = swap.firstColumn.takeIf { it.isNotBlank() },
                        onColumnSelected = { selected ->
                            onSwapsChanged(swaps.mapIndexed { i, s -> if (i == index) s.copy(firstColumn = selected) else s })
                        },
                        label = "First column",
                        enabled = enabled,
                        isError = swap.firstColumn.isBlank(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ColumnDropdown(
                        columns = columns,
                        selectedColumn = swap.secondColumn.takeIf { it.isNotBlank() },
                        onColumnSelected = { selected ->
                            onSwapsChanged(swaps.mapIndexed { i, s -> if (i == index) s.copy(secondColumn = selected) else s })
                        },
                        label = "Second column",
                        enabled = enabled,
                        isError = swap.secondColumn.isBlank(),
                    )
                }
                IconButton(
                    onClick = { onSwapsChanged(swaps.filterIndexed { i, _ -> i != index }) },
                    enabled = enabled,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove swap")
                }
            }
        }
        TextButton(
            onClick = {
                onSwapsChanged(swaps + ColumnPairSwap(firstColumn = "", secondColumn = ""))
            },
            enabled = enabled,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text("Add Swap")
        }
    }
}

/**
 * Editor for the strategy's list of [CompanionTransactionRule]s.
 */
@Composable
internal fun CompanionTransactionRulesEditor(
    rules: List<CompanionTransactionRule>,
    onRulesChanged: (List<CompanionTransactionRule>) -> Unit,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        rules.forEachIndexed { index, rule ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Rule ${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onRulesChanged(rules.filterIndexed { i, _ -> i != index }) },
                            enabled = enabled,
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove rule")
                        }
                    }

                    fun updateRule(transform: (CompanionTransactionRule) -> CompanionTransactionRule) {
                        onRulesChanged(rules.mapIndexed { i, r -> if (i == index) transform(r) else r })
                    }
                    OutlinedTextField(
                        value = rule.name,
                        onValueChange = { newValue -> updateRule { it.copy(name = newValue) } },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        isError = rule.name.isBlank(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = rule.matchAttributeName,
                        onValueChange = { newValue -> updateRule { it.copy(matchAttributeName = newValue) } },
                        label = { Text("Match attribute name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        isError = rule.matchAttributeName.isBlank(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = rule.matchValuePattern,
                        onValueChange = { newValue -> updateRule { it.copy(matchValuePattern = newValue) } },
                        label = { Text("Match value pattern (SQL LIKE)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        isError = rule.matchValuePattern.isBlank(),
                        supportingText = { Text("e.g., ACCRUAL_CHARGE-%") },
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = rule.linkAttributeName,
                        onValueChange = { newValue -> updateRule { it.copy(linkAttributeName = newValue) } },
                        label = { Text("Link attribute name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = rule.companionDescription,
                        onValueChange = { newValue -> updateRule { it.copy(companionDescription = newValue) } },
                        label = { Text("Companion description") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                    )
                }
            }
        }
        TextButton(
            onClick = {
                onRulesChanged(
                    rules +
                        CompanionTransactionRule(
                            name = "",
                            matchAttributeName = "",
                            matchValuePattern = "",
                            linkAttributeName = "",
                            companionDescription = "",
                        ),
                )
            },
            enabled = enabled,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text("Add Rule")
        }
    }
}
