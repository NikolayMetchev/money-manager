package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.csvstrategy.LegGroupRule
import com.moneymanager.domain.model.rules.Extraction
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.domain.model.rules.isComplete
import com.moneymanager.ui.screens.csvstrategy.getSampleValue

/**
 * Editor for the strategy's [LegGroupRule]s: how the source splits one movement — a trade, a conversion —
 * across several rows, and how those rows are put back together. Which rows are legs, and whether each is
 * a debit or a credit, is a "Leg of a group" action on the Rules tab naming the group.
 */
@Composable
internal fun LegGroupsEditor(
    rules: List<LegGroupRule>,
    onRulesChanged: (List<LegGroupRule>) -> Unit,
    columns: List<CsvColumn>,
    firstRow: CsvRow?,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        rules.forEachIndexed { index, rule ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    EditorCardHeader(
                        title = "Group ${index + 1}",
                        removeContentDescription = "Remove leg group",
                        onRemove = { onRulesChanged(rules.filterIndexed { i, _ -> i != index }) },
                        enabled = enabled,
                    )
                    LegGroupRuleEditor(
                        rule = rule,
                        onRuleChanged = { updated -> onRulesChanged(rules.mapIndexed { i, r -> if (i == index) updated else r }) },
                        columns = columns,
                        firstRow = firstRow,
                        enabled = enabled,
                    )
                }
            }
        }
        TextButton(onClick = { onRulesChanged(rules + defaultLegGroupRule()) }, enabled = enabled) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text("Add Leg Group")
        }
    }
}

@Composable
private fun LegGroupRuleEditor(
    rule: LegGroupRule,
    onRuleChanged: (LegGroupRule) -> Unit,
    columns: List<CsvColumn>,
    firstRow: CsvRow?,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = rule.name,
        onValueChange = { onRuleChanged(rule.copy(name = it)) },
        label = { Text("Group name") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        isError = rule.name.isBlank(),
        supportingText = { Text("The name a rule's \"Leg of a group\" action uses") },
    )
    Spacer(modifier = Modifier.height(8.dp))
    KeyPartsEditor(rule.key, { onRuleChanged(rule.copy(key = it)) }, columns, firstRow, enabled)
    LongField(
        value = rule.windowSeconds,
        onValueChange = { onRuleChanged(rule.copy(windowSeconds = it.coerceAtLeast(0))) },
        label = "Window between legs of one event (seconds)",
        enabled = enabled,
    )
    ReconcileWindowField(
        value = rule.reconcileWindowSeconds,
        onValueChange = { onRuleChanged(rule.copy(reconcileWindowSeconds = it?.coerceAtLeast(0))) },
        enabled = enabled,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text("Assembly", style = MaterialTheme.typography.labelMedium)
    Choice("One trade (one asset out, one asset in)", rule.assembly is LegAssembly.Trade, enabled) {
        if (rule.assembly !is LegAssembly.Trade) onRuleChanged(rule.copy(assembly = LegAssembly.Trade()))
    }
    Choice("Through an account, each debit linked to its credit", rule.assembly is LegAssembly.ThroughAccount, enabled) {
        if (rule.assembly !is LegAssembly.ThroughAccount) {
            onRuleChanged(rule.copy(assembly = LegAssembly.ThroughAccount(relationshipTypeName = "conversion")))
        }
    }
    when (val assembly = rule.assembly) {
        is LegAssembly.Trade -> {
            OutlinedTextField(
                value = assembly.description,
                onValueChange = { onRuleChanged(rule.copy(assembly = assembly.copy(description = it))) },
                label = { Text("Trade description ({from} and {to} are the asset codes)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled,
            )
        }

        is LegAssembly.ThroughAccount -> {
            Text(
                "The intermediate account is the rules' \"Intermediate\" account action on each leg. With none, " +
                    "each leg keeps the counterparty its own row names and the legs are only linked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = assembly.relationshipTypeName,
                onValueChange = { onRuleChanged(rule.copy(assembly = assembly.copy(relationshipTypeName = it))) },
                label = { Text("Relationship type name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled,
                isError = assembly.relationshipTypeName.isBlank(),
                supportingText = { Text("Links each debit leg to its credit leg (e.g. \"conversion\")") },
            )
            RowConditionsEditor(
                conditions = assembly.fundingWhen,
                onConditionsChanged = { onRuleChanged(rule.copy(assembly = assembly.copy(fundingWhen = it))) },
                columns = columns,
                enabled = enabled,
                title = "Funding leg when all of these hold (optional; pairs one funding leg with one other leg)",
            )
        }
    }
}

/** The key parts legs of one event must agree on: a column each, optionally cleaned by a pattern's first group. */
@Composable
private fun KeyPartsEditor(
    parts: List<ValueExpr>,
    onPartsChanged: (List<ValueExpr>) -> Unit,
    columns: List<CsvColumn>,
    firstRow: CsvRow?,
    enabled: Boolean,
) {
    Text("Legs of one event also agree on (optional)", style = MaterialTheme.typography.labelMedium)
    parts.forEachIndexed { index, part ->
        fun update(updated: ValueExpr) = onPartsChanged(parts.mapIndexed { i, p -> if (i == index) updated else p })
        EditorCardHeader(
            title = "Key part ${index + 1}",
            removeContentDescription = "Remove key part",
            onRemove = { onPartsChanged(parts.filterIndexed { i, _ -> i != index }) },
            enabled = enabled,
        )
        ColumnDropdown(
            columns = columns,
            selectedColumn = part.primaryPath.takeIf { it.isNotBlank() },
            onColumnSelected = { update(part.copy(paths = listOf(it) + part.paths.drop(1))) },
            label = "Column",
            sampleValue = getSampleValue(columns, firstRow, part.primaryPath),
            enabled = enabled,
            isError = part.primaryPath.isBlank(),
        )
        OutlinedTextField(
            value = part.extraction?.pattern.orEmpty(),
            onValueChange = { pattern ->
                update(
                    part.copy(
                        extraction =
                            pattern.takeIf { it.isNotBlank() }?.let {
                                Extraction(
                                    it,
                                    part.extraction?.outputTemplate ?: "$1",
                                )
                            },
                    ),
                )
            },
            label = { Text("Pattern (optional; its first group is the key)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
        )
    }
    TextButton(onClick = { onPartsChanged(parts + ValueExpr(listOf(columns.firstOrNull()?.originalName.orEmpty()))) }, enabled = enabled) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
        Text("Add Key Part")
    }
}

@Composable
private fun Choice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Whether every part of [this] rule is filled in. */
internal fun LegGroupRule.isComplete(): Boolean =
    name.isNotBlank() &&
        key.all { it.primaryPath.isNotBlank() } &&
        when (val assembly = assembly) {
            is LegAssembly.Trade -> true
            is LegAssembly.ThroughAccount -> assembly.fundingWhen.all { it.isComplete() } && assembly.relationshipTypeName.isNotBlank()
        }

private fun defaultLegGroupRule(): LegGroupRule = LegGroupRule(name = "", assembly = LegAssembly.Trade())

/**
 * A numeric `OutlinedTextField` bound to a [Long]. Keeps its own text buffer so intermediate
 * empty/invalid input is shown without corrupting the model; commits only parseable values.
 */
@Composable
internal fun LongField(
    value: Long,
    onValueChange: (Long) -> Unit,
    label: String,
    enabled: Boolean,
) {
    // Keyed on value so an external change to the backing Long resyncs the buffer, while
    // intermediate invalid input (which doesn't change value) is preserved.
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.trim().toLongOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = text.trim().toLongOrNull() == null,
    )
}

/** The optional reconcile window, in seconds: blank means off. */
@Composable
private fun ReconcileWindowField(
    value: Long?,
    onValueChange: (Long?) -> Unit,
    enabled: Boolean,
) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            val trimmed = it.trim()
            if (trimmed.isEmpty()) onValueChange(null) else trimmed.toLongOrNull()?.let(onValueChange)
        },
        label = { Text("Skip events another source already recorded within (seconds, optional)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = text.isNotBlank() && text.trim().toLongOrNull() == null,
    )
}
