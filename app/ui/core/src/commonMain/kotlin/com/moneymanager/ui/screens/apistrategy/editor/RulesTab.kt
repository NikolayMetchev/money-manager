package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.apistrategy.BuiltInCounterpartyRule
import com.moneymanager.domain.model.apistrategy.PredicateOp
import com.moneymanager.domain.model.apistrategy.RulePredicate
import com.moneymanager.domain.model.apistrategy.RuleSign
import com.moneymanager.ui.screens.apistrategy.JsonPathEntry

@Composable
internal fun RulesTab(
    state: ApiStrategyEditorState,
    txJsonPaths: List<JsonPathEntry>,
    onRequestPick: PathPicker,
    enabled: Boolean,
) {
    val rules = state.builtInCounterpartyRules
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text =
                "Declarative rules that consolidate matching transactions into a single built-in " +
                    "counterparty account (e.g. ATM). All predicates must match.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rules.forEachIndexed { index, rule ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors()) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EditorCardHeader(
                        title = rule.name.ifBlank { "Rule ${index + 1}" },
                        onRemove = { state.builtInCounterpartyRules = rules.toMutableList().also { it.removeAt(index) } },
                        enabled = enabled,
                    )
                    RuleEditor(
                        rule = rule,
                        onChange = { updated -> state.builtInCounterpartyRules = rules.toMutableList().also { it[index] = updated } },
                        txJsonPaths = txJsonPaths,
                        onRequestPick = onRequestPick,
                        enabled = enabled,
                    )
                }
            }
        }
        TextButton(onClick = { state.builtInCounterpartyRules = rules + BuiltInCounterpartyRule(name = "") }, enabled = enabled) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Add rule")
        }
    }
}

@Composable
private fun RuleEditor(
    rule: BuiltInCounterpartyRule,
    onChange: (BuiltInCounterpartyRule) -> Unit,
    txJsonPaths: List<JsonPathEntry>,
    onRequestPick: PathPicker,
    enabled: Boolean,
) {
    TextFieldRow(
        label = "Name",
        value = rule.name,
        onValueChange = { onChange(rule.copy(name = it)) },
        enabled = enabled,
        isError = rule.name.isBlank(),
    )
    EnumDropdown(
        label = "Only when sign",
        options = RuleSign.entries,
        selected = rule.onlyWhenSign,
        onSelect = { onChange(rule.copy(onlyWhenSign = it)) },
        optionLabel = { it.name },
        enabled = enabled,
    )
    Text("Predicates", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    rule.predicates.forEachIndexed { index, predicate ->
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), colors = CardDefaults.cardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EditorCardHeader(
                    title = "Predicate ${index + 1}",
                    onRemove = { onChange(rule.copy(predicates = rule.predicates.toMutableList().also { it.removeAt(index) })) },
                    enabled = enabled,
                )
                PathFieldRow(
                    "Path",
                    predicate.path,
                    {
                        onChange(
                            rule.copy(
                                predicates =
                                    rule.predicates.toMutableList().also { l ->
                                        l[index] = predicate.copy(path = it)
                                    },
                            ),
                        )
                    },
                    txJsonPaths,
                    onRequestPick,
                    enabled,
                )
                EnumDropdown(
                    label = "Operator",
                    options = PredicateOp.entries,
                    selected = predicate.op,
                    onSelect = { op ->
                        onChange(rule.copy(predicates = rule.predicates.toMutableList().also { it[index] = predicate.copy(op = op) }))
                    },
                    optionLabel = { it.name },
                    enabled = enabled,
                )
                if (predicate.op.requiresValue()) {
                    TextFieldRow(
                        label = "Value",
                        value = predicate.value.orEmpty(),
                        onValueChange = { v ->
                            onChange(
                                rule.copy(
                                    predicates =
                                        rule.predicates.toMutableList().also {
                                            it[index] =
                                                predicate.copy(value = v.ifBlank { null })
                                        },
                                ),
                            )
                        },
                        enabled = enabled,
                        isError = predicate.value.isNullOrBlank(),
                    )
                }
            }
        }
    }
    TextButton(
        onClick = { onChange(rule.copy(predicates = rule.predicates + RulePredicate(path = "", op = PredicateOp.EXISTS))) },
        enabled = enabled,
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text("Add predicate")
    }
}
