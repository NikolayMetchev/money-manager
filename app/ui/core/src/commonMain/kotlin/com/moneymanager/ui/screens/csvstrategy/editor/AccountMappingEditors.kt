package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.RegexAccountMapping
import com.moneymanager.domain.model.csvstrategy.RegexRule
import com.moneymanager.domain.model.csvstrategy.RowCondition
import com.moneymanager.domain.model.csvstrategy.TemplateAccountMapping
import com.moneymanager.ui.screens.csvstrategy.getSampleValue

/**
 * Radio-button row selecting how the target account is resolved.
 */
@Composable
internal fun TargetAccountModeSelector(
    selected: TargetAccountMode,
    onSelected: (TargetAccountMode) -> Unit,
    enabled: Boolean,
) {
    val labels =
        listOf(
            TargetAccountMode.DIRECT_LOOKUP to "Lookup",
            TargetAccountMode.REGEX_MATCH to "Regex",
            TargetAccountMode.TEMPLATE to "Template",
            TargetAccountMode.CONDITIONAL to "Conditional",
        )
    Column {
        labels.chunked(2).forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                row.forEach { (mode, label) ->
                    RadioButton(
                        selected = selected == mode,
                        onClick = { onSelected(mode) },
                        enabled = enabled,
                    )
                    Text(label, modifier = Modifier.padding(end = 16.dp))
                }
            }
        }
    }
}

/**
 * Editor for an account mapping that templates a column value into an account name
 * (`prefix + columnValue + suffix`).
 */
@Composable
internal fun TemplateAccountMappingEditor(
    columnName: String?,
    onColumnChanged: (String) -> Unit,
    prefix: String,
    onPrefixChanged: (String) -> Unit,
    suffix: String,
    onSuffixChanged: (String) -> Unit,
    columns: List<CsvColumn>,
    firstRow: CsvRow?,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ColumnDropdown(
            columns = columns,
            selectedColumn = columnName,
            onColumnSelected = onColumnChanged,
            label = "Column for account name",
            sampleValue = getSampleValue(columns, firstRow, columnName),
            enabled = enabled,
            isError = columnName == null,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = prefix,
            onValueChange = onPrefixChanged,
            label = { Text("Prefix (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = suffix,
            onValueChange = onSuffixChanged,
            label = { Text("Suffix (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
        )
        getSampleValue(columns, firstRow, columnName)?.let { sample ->
            Text(
                "Account: $prefix$sample$suffix",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Editor for a single non-conditional account mapping used as a branch of a
 * conditional mapping. The kind picker excludes the conditional type, so
 * nesting is bounded to one level.
 */
@Composable
internal fun LeafAccountMappingEditor(
    label: String,
    mapping: FieldMapping,
    onMappingChanged: (FieldMapping) -> Unit,
    columns: List<CsvColumn>,
    rows: List<CsvRow>,
    firstRow: CsvRow?,
    enabled: Boolean,
) {
    val kind =
        when (mapping) {
            is RegexAccountMapping -> LeafAccountKind.REGEX
            is TemplateAccountMapping -> LeafAccountKind.TEMPLATE
            else -> LeafAccountKind.LOOKUP
        }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            LeafAccountKind.entries.forEach { k ->
                RadioButton(
                    selected = k == kind,
                    onClick = { if (k != kind) onMappingChanged(defaultLeafAccountMapping(k, mapping.fieldType, mapping)) },
                    enabled = enabled,
                )
                Text(k.label(), modifier = Modifier.padding(end = 8.dp))
            }
        }
        when (mapping) {
            is AccountLookupMapping -> {
                ColumnDropdown(
                    columns = columns,
                    selectedColumn = mapping.columnName.takeIf { it.isNotBlank() },
                    onColumnSelected = { onMappingChanged(mapping.copy(columnName = it)) },
                    label = "Column for account name",
                    sampleValue = getSampleValue(columns, firstRow, mapping.columnName),
                    enabled = enabled,
                    isError = mapping.columnName.isBlank(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                OptionalColumnDropdown(
                    columns = columns,
                    selectedColumn = mapping.fallbackColumns.firstOrNull(),
                    onColumnSelected = { selected ->
                        onMappingChanged(mapping.copy(fallbackColumns = if (selected != null) listOf(selected) else emptyList()))
                    },
                    label = "Fallback column",
                    enabled = enabled,
                )
            }
            is RegexAccountMapping -> {
                ColumnDropdown(
                    columns = columns,
                    selectedColumn = mapping.columnName.takeIf { it.isNotBlank() },
                    onColumnSelected = { onMappingChanged(mapping.copy(columnName = it)) },
                    label = "Column for account name",
                    sampleValue = getSampleValue(columns, firstRow, mapping.columnName),
                    enabled = enabled,
                    isError = mapping.columnName.isBlank(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                RegexRulesEditor(
                    rules = mapping.rules,
                    onRulesChanged = { onMappingChanged(mapping.copy(rules = it)) },
                    columnName = mapping.columnName.takeIf { it.isNotBlank() },
                    columns = columns,
                    rows = rows,
                    enabled = enabled,
                )
            }
            is TemplateAccountMapping -> {
                TemplateAccountMappingEditor(
                    columnName = mapping.columnName.takeIf { it.isNotBlank() },
                    onColumnChanged = { onMappingChanged(mapping.copy(columnName = it)) },
                    prefix = mapping.prefix,
                    onPrefixChanged = { onMappingChanged(mapping.copy(prefix = it)) },
                    suffix = mapping.suffix,
                    onSuffixChanged = { onMappingChanged(mapping.copy(suffix = it)) },
                    columns = columns,
                    firstRow = firstRow,
                    enabled = enabled,
                )
            }
            else -> Unit
        }
    }
}

/**
 * Editor for a conditional account mapping: a condition list plus two nested
 * leaf-account editors for the true/false branches.
 */
@Composable
internal fun ConditionalAccountMappingEditor(
    conditions: List<RowCondition>,
    onConditionsChanged: (List<RowCondition>) -> Unit,
    whenTrue: FieldMapping,
    onWhenTrueChanged: (FieldMapping) -> Unit,
    whenFalse: FieldMapping,
    onWhenFalseChanged: (FieldMapping) -> Unit,
    columns: List<CsvColumn>,
    rows: List<CsvRow>,
    firstRow: CsvRow?,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        RowConditionsEditor(
            conditions = conditions,
            onConditionsChanged = onConditionsChanged,
            columns = columns,
            enabled = enabled,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LeafAccountMappingEditor(
            label = "When conditions match",
            mapping = whenTrue,
            onMappingChanged = onWhenTrueChanged,
            columns = columns,
            rows = rows,
            firstRow = firstRow,
            enabled = enabled,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LeafAccountMappingEditor(
            label = "Otherwise",
            mapping = whenFalse,
            onMappingChanged = onWhenFalseChanged,
            columns = columns,
            rows = rows,
            firstRow = firstRow,
            enabled = enabled,
        )
    }
}

/**
 * Editor for regex rules that map column values to account names.
 * Shows a list of rules with pattern and account name inputs, plus a preview of matches.
 */
@Composable
internal fun RegexRulesEditor(
    rules: List<RegexRule>,
    onRulesChanged: (List<RegexRule>) -> Unit,
    columnName: String?,
    columns: List<CsvColumn>,
    rows: List<CsvRow>,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Regex Rules",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Rules are evaluated in order. First match wins. Case-insensitive.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Get column index for preview
        val columnIndex =
            columnName?.let { name ->
                columns.find { it.originalName == name }?.columnIndex
            }

        // Show each rule with its matches
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
                            onClick = {
                                onRulesChanged(rules.filterIndexed { i, _ -> i != index })
                            },
                            enabled = enabled,
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove rule",
                            )
                        }
                    }

                    OutlinedTextField(
                        value = rule.pattern,
                        onValueChange = { newPattern ->
                            onRulesChanged(
                                rules.mapIndexed { i, r ->
                                    if (i == index) r.copy(pattern = newPattern) else r
                                },
                            )
                        },
                        label = { Text("Regex Pattern") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        supportingText = { Text("e.g., .*sample.*") },
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    val isAccountNameError = rule.accountName.isBlank()
                    OutlinedTextField(
                        value = rule.accountName,
                        onValueChange = { newName ->
                            onRulesChanged(
                                rules.mapIndexed { i, r ->
                                    if (i == index) r.copy(accountName = newName) else r
                                },
                            )
                        },
                        label = {
                            Text(
                                "Target Account Name *",
                                color =
                                    if (isAccountNameError) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        isError = isAccountNameError,
                        supportingText = {
                            Text(
                                if (isAccountNameError) "Required" else "Account name when pattern matches",
                                color =
                                    if (isAccountNameError) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        },
                    )

                    // Show preview of matching values
                    if (columnIndex != null && rule.pattern.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val matchingValues =
                            remember(rule.pattern, rows, columnIndex) {
                                try {
                                    val regex = Regex(rule.pattern, RegexOption.IGNORE_CASE)
                                    rows
                                        .mapNotNull { row ->
                                            row.values.getOrNull(columnIndex)?.takeIf { it.isNotBlank() }
                                        }.filter { regex.containsMatchIn(it) }
                                        .distinct()
                                } catch (_: Exception) {
                                    emptyList()
                                }
                            }
                        val matchCount =
                            remember(rule.pattern, rows, columnIndex) {
                                try {
                                    val regex = Regex(rule.pattern, RegexOption.IGNORE_CASE)
                                    rows.count { row ->
                                        row.values.getOrNull(columnIndex)?.let { regex.containsMatchIn(it) } == true
                                    }
                                } catch (_: Exception) {
                                    0
                                }
                            }
                        if (matchCount > 0) {
                            Text(
                                "Matches $matchCount rows → \"${rule.accountName}\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 100.dp)
                                        .verticalScroll(rememberScrollState()),
                            ) {
                                matchingValues.forEach { value ->
                                    Text(
                                        "  • $value",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        } else {
                            Text(
                                "No matches",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        // Add Rule button
        TextButton(
            onClick = {
                onRulesChanged(rules + RegexRule(pattern = "", accountName = ""))
            },
            enabled = enabled,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.padding(end = 4.dp),
            )
            Text("Add Rule")
        }

        // Summary of unmatched rows
        if (rules.isNotEmpty() && columnIndex != null) {
            Spacer(modifier = Modifier.height(8.dp))
            val unmatchedCount =
                remember(rules, rows, columnIndex) {
                    rows.count { row ->
                        val value = row.values.getOrNull(columnIndex)
                        if (value.isNullOrBlank()) return@count false
                        rules.none { rule ->
                            if (rule.pattern.isBlank()) return@none false
                            try {
                                val regex = Regex(rule.pattern, RegexOption.IGNORE_CASE)
                                regex.containsMatchIn(value)
                            } catch (_: Exception) {
                                false
                            }
                        }
                    }
                }
            if (unmatchedCount > 0) {
                Text(
                    "Unmatched: $unmatchedCount rows → use column value",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
