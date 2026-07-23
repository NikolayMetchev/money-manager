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
import androidx.compose.material3.Switch
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
import com.moneymanager.domain.model.csvstrategy.ContentMatchRule
import com.moneymanager.domain.model.csvstrategy.ConversionAccountRule
import com.moneymanager.domain.model.csvstrategy.ConversionConfig
import com.moneymanager.ui.screens.csvstrategy.getSampleValue

/**
 * Editor for the strategy's list of [ContentMatchRule]s — content-based match rules that auto-detect
 * this strategy from a sampled row when the column set is fixed and cannot distinguish formats (QIF).
 */
@Composable
internal fun ContentMatchRulesEditor(
    rules: List<ContentMatchRule>,
    onRulesChanged: (List<ContentMatchRule>) -> Unit,
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
                        title = "Rule ${index + 1}",
                        removeContentDescription = "Remove content-match rule",
                        onRemove = { onRulesChanged(rules.filterIndexed { i, _ -> i != index }) },
                        enabled = enabled,
                    )

                    fun updateRule(transform: (ContentMatchRule) -> ContentMatchRule) {
                        onRulesChanged(rules.mapIndexed { i, r -> if (i == index) transform(r) else r })
                    }
                    ColumnDropdown(
                        columns = columns,
                        selectedColumn = rule.columnName.takeIf { it.isNotBlank() },
                        onColumnSelected = { selected -> updateRule { it.copy(columnName = selected) } },
                        label = "Column",
                        sampleValue = getSampleValue(columns, firstRow, rule.columnName),
                        enabled = enabled,
                        isError = rule.columnName.isBlank(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = rule.pattern,
                        onValueChange = { newValue -> updateRule { it.copy(pattern = newValue) } },
                        label = { Text("Match pattern (regex, case-insensitive)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        isError = rule.pattern.isBlank(),
                        supportingText = { Text("A sampled row matching this value selects the strategy") },
                    )
                }
            }
        }
        TextButton(
            onClick = { onRulesChanged(rules + ContentMatchRule(columnName = "", pattern = "")) },
            enabled = enabled,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text("Add Rule")
        }
    }
}

/**
 * Editor for the optional [ConversionConfig]: a switch that creates/clears the config, and — when on —
 * the full set of fields describing how debited/credited row pairs route through a shared conversion
 * account and link as one conversion event.
 */
@Composable
internal fun ConversionConfigEditor(
    config: ConversionConfig?,
    onConfigChanged: (ConversionConfig?) -> Unit,
    columns: List<CsvColumn>,
    firstRow: CsvRow?,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = config != null,
                onCheckedChange = { checked ->
                    onConfigChanged(if (checked) defaultConversionConfig() else null)
                },
                enabled = enabled,
            )
            Text("Enable asset conversions", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
        }

        if (config == null) return

        // Keep the model invariant (a name or at least one rule) so editing never throws.
        fun update(transform: (ConversionConfig) -> ConversionConfig) {
            val next = transform(config)
            onConfigChanged(
                if (next.conversionAccountName == null && next.conversionAccountRules.isEmpty()) {
                    next.copy(conversionAccountName = "")
                } else {
                    next
                },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        ColumnDropdown(
            columns = columns,
            selectedColumn = config.signalColumn.takeIf { it.isNotBlank() },
            onColumnSelected = { selected -> update { it.copy(signalColumn = selected) } },
            label = "Signal column",
            sampleValue = getSampleValue(columns, firstRow, config.signalColumn),
            enabled = enabled,
            isError = config.signalColumn.isBlank(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = config.debitPattern,
            onValueChange = { newValue -> update { it.copy(debitPattern = newValue) } },
            label = { Text("Debit pattern (regex)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
            isError = config.debitPattern.isBlank(),
            supportingText = { Text("Matches the signal column of the leg leaving the owner account") },
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = config.creditPattern,
            onValueChange = { newValue -> update { it.copy(creditPattern = newValue) } },
            label = { Text("Credit pattern (regex)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
            isError = config.creditPattern.isBlank(),
            supportingText = { Text("Matches the signal column of the leg received into the owner account") },
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = config.conversionAccountName.orEmpty(),
            onValueChange = { newValue -> update { it.copy(conversionAccountName = newValue) } },
            label = { Text("Conversion account name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
            isError = config.conversionAccountName.isNullOrBlank() && config.conversionAccountRules.isEmpty(),
            supportingText = {
                Text("Shared counterparty account the legs route through. Leave blank only if the rules below cover every leg.")
            },
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text("Per-value account routing (optional)", style = MaterialTheme.typography.labelMedium)
        Text(
            "Override the conversion account for legs whose column matches a pattern; first match wins.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ConversionAccountRulesEditor(
            rules = config.conversionAccountRules,
            onRulesChanged = { updated -> update { it.copy(conversionAccountRules = updated) } },
            columns = columns,
            firstRow = firstRow,
            enabled = enabled,
        )

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = config.pairingKeyPattern.orEmpty(),
            onValueChange = { newValue -> update { it.copy(pairingKeyPattern = newValue.ifBlank { null }) } },
            label = { Text("Pairing key pattern (optional regex)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
            supportingText = { Text("First capture group on the signal column becomes the pairing key") },
        )
        Spacer(modifier = Modifier.height(4.dp))
        LongField(
            value = config.pairingWindowSeconds,
            onValueChange = { newValue -> update { it.copy(pairingWindowSeconds = newValue) } },
            label = "Pairing window (seconds)",
            enabled = enabled,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = config.relationshipTypeName,
            onValueChange = { newValue -> update { it.copy(relationshipTypeName = newValue) } },
            label = { Text("Relationship type name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
            isError = config.relationshipTypeName.isBlank(),
            supportingText = { Text("Links each debit leg to its credit leg (e.g. \"conversion\")") },
        )
    }
}

@Composable
private fun ConversionAccountRulesEditor(
    rules: List<ConversionAccountRule>,
    onRulesChanged: (List<ConversionAccountRule>) -> Unit,
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
                        title = "Route ${index + 1}",
                        removeContentDescription = "Remove routing rule",
                        onRemove = { onRulesChanged(rules.filterIndexed { i, _ -> i != index }) },
                        enabled = enabled,
                    )

                    fun updateRule(transform: (ConversionAccountRule) -> ConversionAccountRule) {
                        onRulesChanged(rules.mapIndexed { i, r -> if (i == index) transform(r) else r })
                    }
                    ColumnDropdown(
                        columns = columns,
                        selectedColumn = rule.column.takeIf { it.isNotBlank() },
                        onColumnSelected = { selected -> updateRule { it.copy(column = selected) } },
                        label = "Column",
                        sampleValue = getSampleValue(columns, firstRow, rule.column),
                        enabled = enabled,
                        isError = rule.column.isBlank(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = rule.pattern,
                        onValueChange = { newValue -> updateRule { it.copy(pattern = newValue) } },
                        label = { Text("Pattern (regex)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        isError = rule.pattern.isBlank(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = rule.accountName,
                        onValueChange = { newValue -> updateRule { it.copy(accountName = newValue) } },
                        label = { Text("Account name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        isError = rule.accountName.isBlank(),
                    )
                }
            }
        }
        TextButton(
            onClick = { onRulesChanged(rules + ConversionAccountRule(column = "", pattern = "", accountName = "")) },
            enabled = enabled,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text("Add Route")
        }
    }
}

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

private fun defaultConversionConfig(): ConversionConfig =
    ConversionConfig(
        signalColumn = "",
        debitPattern = "",
        creditPattern = "",
        conversionAccountName = "",
        pairingWindowSeconds = 60,
        relationshipTypeName = "conversion",
    )
