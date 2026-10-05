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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.AccountRule
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.domain.model.rules.isComplete
import com.moneymanager.ui.components.rules.ExtractionEditor
import com.moneymanager.ui.screens.csvstrategy.getSampleValue

/** How a rule decides it applies, beyond its conditions. */
private enum class RuleMatch(
    val label: String,
) {
    ALWAYS("Always"),
    PATTERN("When a pattern matches"),
    ATTRIBUTE("When an account's attribute matches"),
}

private val AccountRule.match: RuleMatch
    get() =
        when {
            attributeTypeName != null -> RuleMatch.ATTRIBUTE
            pattern != null -> RuleMatch.PATTERN
            else -> RuleMatch.ALWAYS
        }

/** A new rule naming the account after [column]'s value. */
internal fun defaultAccountRule(column: String?): AccountRule = AccountRule(value = ValueExpr(listOf(column.orEmpty())))

/** Whether [this] rule has every input it needs to be saved. */
internal fun AccountRule.isComplete(): Boolean =
    value.paths.isNotEmpty() &&
        value.paths.all { it.isNotBlank() } &&
        conditions.all { it.isComplete() } &&
        when (match) {
            RuleMatch.ATTRIBUTE -> !attributeTypeName.isNullOrBlank()
            RuleMatch.PATTERN -> !pattern.isNullOrBlank() && runCatching { Regex(pattern!!) }.isSuccess && name.isNotBlank()
            RuleMatch.ALWAYS -> name.isNotBlank()
        }

/** [this] rules keeping only the columns the uploaded file still has; a rule left with none is dropped. */
internal fun List<AccountRule>.keepColumnsPresentIn(columns: Set<String>): List<AccountRule> =
    mapNotNull { rule ->
        val paths = rule.value.paths.filter { it in columns }
        val conditions = rule.conditions.keepPresentIn(columns)
        rule
            .takeIf { paths.isNotEmpty() && conditions.size == rule.conditions.size }
            ?.copy(value = rule.value.copy(paths = paths))
    }

/**
 * Editor for an ordered account-rule list (see [AccountRule]): the first rule that applies names the
 * account. Each rule reads a value from the row (a column, falling back to others when blank), may be
 * limited by row conditions and a pattern, and names the account from a template.
 */
@Composable
internal fun AccountRulesEditor(
    rules: List<AccountRule>,
    onRulesChanged: (List<AccountRule>) -> Unit,
    columns: List<CsvColumn>,
    rows: List<CsvRow>,
    firstRow: CsvRow?,
    existingAttributeTypeNames: List<String>,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Rules are tried in order; the first that applies names the account. Patterns are case-insensitive.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rules.forEachIndexed { index, rule ->
            fun update(updated: AccountRule) = onRulesChanged(rules.mapIndexed { i, r -> if (i == index) updated else r })

            fun move(by: Int) = onRulesChanged(rules.toMutableList().also { it.add(index + by, it.removeAt(index)) })
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Rule ${index + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { move(-1) }, enabled = enabled && index > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move rule up")
                        }
                        IconButton(onClick = { move(1) }, enabled = enabled && index < rules.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move rule down")
                        }
                        IconButton(onClick = { onRulesChanged(rules.filterIndexed { i, _ -> i != index }) }, enabled = enabled) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove rule")
                        }
                    }
                    AccountRuleEditor(rule, ::update, columns, rows, firstRow, existingAttributeTypeNames, enabled)
                }
            }
        }
        TextButton(onClick = { onRulesChanged(rules + defaultAccountRule(rules.lastOrNull()?.value?.primaryPath)) }, enabled = enabled) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text("Add Rule")
        }
    }
}

@Composable
private fun AccountRuleEditor(
    rule: AccountRule,
    onRuleChanged: (AccountRule) -> Unit,
    columns: List<CsvColumn>,
    rows: List<CsvRow>,
    firstRow: CsvRow?,
    existingAttributeTypeNames: List<String>,
    enabled: Boolean,
) {
    RowConditionsEditor(
        conditions = rule.conditions,
        onConditionsChanged = { onRuleChanged(rule.copy(conditions = it)) },
        columns = columns,
        enabled = enabled,
        title = "Only when (all must match; none = always)",
    )
    Spacer(modifier = Modifier.height(4.dp))
    rule.value.paths.forEachIndexed { index, path ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                ColumnDropdown(
                    columns = columns,
                    selectedColumn = path.takeIf { it.isNotBlank() },
                    onColumnSelected = { column ->
                        onRuleChanged(
                            rule.copy(
                                value =
                                    rule.value.copy(
                                        paths =
                                            rule.value.paths.mapIndexed { i, p ->
                                                if (i ==
                                                    index
                                                ) {
                                                    column
                                                } else {
                                                    p
                                                }
                                            },
                                    ),
                            ),
                        )
                    },
                    label =
                        if (index ==
                            0
                        ) {
                            "Column the account is named from"
                        } else {
                            "Fallback column ${index + 1} (when the ones above are blank)"
                        },
                    sampleValue = getSampleValue(columns, firstRow, path),
                    enabled = enabled,
                    isError = path.isBlank(),
                )
            }
            if (index > 0) {
                IconButton(
                    onClick = {
                        onRuleChanged(
                            rule.copy(
                                value =
                                    rule.value.copy(
                                        paths =
                                            rule.value.paths.filterIndexed {
                                                i,
                                                _,
                                                ->
                                                i != index
                                            },
                                    ),
                            ),
                        )
                    },
                    enabled = enabled,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove fallback column")
                }
            }
        }
    }
    TextButton(onClick = { onRuleChanged(rule.copy(value = rule.value.copy(paths = rule.value.paths + ""))) }, enabled = enabled) {
        Text("Add fallback column")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = rule.trim, onCheckedChange = { onRuleChanged(rule.copy(trim = it)) }, enabled = enabled)
        Text("Trim surrounding whitespace", style = MaterialTheme.typography.bodySmall)
    }
    RuleMatch.entries.forEach { match ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = rule.match == match,
                onClick = {
                    onRuleChanged(
                        when (match) {
                            RuleMatch.ALWAYS -> rule.copy(pattern = null, attributeTypeName = null)
                            RuleMatch.PATTERN -> rule.copy(pattern = rule.pattern ?: "", attributeTypeName = null)
                            RuleMatch.ATTRIBUTE ->
                                rule.copy(
                                    pattern = null,
                                    attributeTypeName =
                                        rule.attributeTypeName ?: existingAttributeTypeNames.firstOrNull().orEmpty(),
                                )
                        },
                    )
                },
                enabled = enabled,
            )
            Text(match.label, style = MaterialTheme.typography.bodySmall)
        }
    }
    when (rule.match) {
        RuleMatch.PATTERN -> {
            OutlinedTextField(
                value = rule.pattern.orEmpty(),
                onValueChange = { onRuleChanged(rule.copy(pattern = it)) },
                label = { Text("Pattern (regex)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled,
                isError = rule.pattern.isNullOrBlank() || runCatching { Regex(rule.pattern!!) }.isFailure,
            )
            PatternPreview(rule, columns, rows)
        }
        RuleMatch.ATTRIBUTE ->
            OutlinedTextField(
                value = rule.attributeTypeName.orEmpty(),
                onValueChange = { onRuleChanged(rule.copy(attributeTypeName = it)) },
                label = { Text("Account attribute holding the regexes") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled,
                isError = rule.attributeTypeName.isNullOrBlank(),
                supportingText = { Text("Known: ${existingAttributeTypeNames.joinToString()}") },
            )
        RuleMatch.ALWAYS -> Unit
    }
    if (rule.match != RuleMatch.ATTRIBUTE) {
        OutlinedTextField(
            value = rule.name,
            onValueChange = { onRuleChanged(rule.copy(name = it)) },
            label = { Text("Account name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
            isError = rule.name.isBlank(),
            supportingText = { Text($$"$${AccountRule.VALUE_PLACEHOLDER} is the column value; $1… are the pattern's captures") },
        )
        if (rule.match == RuleMatch.PATTERN) {
            OutlinedTextField(
                value = rule.fallbackName.orEmpty(),
                onValueChange = { onRuleChanged(rule.copy(fallbackName = it.ifBlank { null })) },
                label = { Text("Account name when the captures come out empty (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled,
            )
        }
        ExtractionFields(rule, onRuleChanged, enabled)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = rule.counterpartyIsPerson,
            onCheckedChange = { onRuleChanged(rule.copy(counterpartyIsPerson = it, personName = rule.personName.takeIf { _ -> it })) },
            enabled = enabled,
        )
        Text("The account belongs to a person", style = MaterialTheme.typography.bodySmall)
    }
    if (rule.counterpartyIsPerson) {
        OutlinedTextField(
            value = rule.personName.orEmpty(),
            onValueChange = { onRuleChanged(rule.copy(personName = it.ifBlank { null })) },
            label = { Text("Person name (optional; defaults to the account name)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = rule.counterpartyIsUnidentified,
            onCheckedChange = { onRuleChanged(rule.copy(counterpartyIsUnidentified = it)) },
            enabled = enabled,
        )
        Text(
            "The name is a placeholder: reconcile against another source's record of the movement",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The rule value's optional cleanup regex (applied before the value is substituted into the name). */
@Composable
private fun ExtractionFields(
    rule: AccountRule,
    onRuleChanged: (AccountRule) -> Unit,
    enabled: Boolean,
) = ExtractionEditor(
    label = "Clean the value with a regex first",
    extraction = rule.value.extraction,
    onChange = { onRuleChanged(rule.copy(value = rule.value.copy(extraction = it))) },
    enabled = enabled,
)

/** How many rows a pattern rule's column matches, and a few of the matched values. */
@Composable
private fun PatternPreview(
    rule: AccountRule,
    columns: List<CsvColumn>,
    rows: List<CsvRow>,
) {
    val columnIndex = columns.find { it.originalName == rule.value.primaryPath }?.columnIndex ?: return
    val pattern = rule.pattern?.takeIf { it.isNotBlank() } ?: return
    val (matchCount, examples) =
        remember(pattern, rows, columnIndex) {
            val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return@remember 0 to emptyList()
            val matched =
                rows.mapNotNull { row ->
                    row.values.getOrNull(columnIndex)?.takeIf { it.isNotBlank() && regex.containsMatchIn(it) }
                }
            matched.size to matched.distinct().take(PREVIEW_EXAMPLES)
        }
    Text(
        if (matchCount > 0) "Matches $matchCount rows, e.g. ${examples.joinToString(" · ")}" else "No matches",
        style = MaterialTheme.typography.bodySmall,
        color = if (matchCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}

private const val PREVIEW_EXAMPLES = 3
