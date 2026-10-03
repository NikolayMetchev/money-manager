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
import com.moneymanager.domain.model.apistrategy.RuleSign
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.ui.components.rules.ConditionsEditor
import com.moneymanager.ui.screens.apistrategy.JsonPathEntry

@Composable
internal fun RulesTab(
    state: ApiStrategyEditorState,
    txJsonPaths: List<JsonPathEntry>,
    onRequestPick: PathPicker,
    enabled: Boolean,
) {
    val rules = state.config.builtInCounterpartyRules
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
                        onRemove = { state.updateConfig { copy(builtInCounterpartyRules = builtInCounterpartyRules.minusAt(index)) } },
                        enabled = enabled,
                    )
                    RuleEditor(
                        rule = rule,
                        onChange = { updated ->
                            state.updateConfig { copy(builtInCounterpartyRules = builtInCounterpartyRules.replacingAt(index, updated)) }
                        },
                        txJsonPaths = txJsonPaths,
                        onRequestPick = onRequestPick,
                        enabled = enabled,
                    )
                }
            }
        }
        TextButton(
            onClick = {
                state.updateConfig {
                    copy(
                        builtInCounterpartyRules = builtInCounterpartyRules + BuiltInCounterpartyRule(name = ""),
                    )
                }
            },
            enabled = enabled,
        ) {
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
    ConditionsEditor(
        title = "Predicates (all must match)",
        conditions = rule.predicates,
        onConditionsChanged = { onChange(rule.copy(predicates = it)) },
        pathField = jsonPathField(txJsonPaths, onRequestPick, enabled),
        enabled = enabled,
        newCondition = { Condition("", ConditionOp.EXISTS) },
        pathLabel = "Path",
        addLabel = "Add predicate",
    )
}
