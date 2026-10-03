@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components.rules

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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.ConditionOperand
import com.moneymanager.domain.model.rules.isStructural
import com.moneymanager.domain.model.rules.operand
import com.moneymanager.domain.model.rules.withOp

/** The ops a flat row of columns supports — everything but the JSON-structure ops. */
val FLAT_CONDITION_OPS: List<ConditionOp> = ConditionOp.entries.filterNot { it.isStructural }

/**
 * Editor for a list of [Condition]s — the one condition editor every strategy editor uses. [title]
 * states how the list combines ("all must match", "any matches"…); [newCondition] seeds an added row.
 */
@Composable
fun ConditionsEditor(
    title: String,
    conditions: List<Condition>,
    onConditionsChanged: (List<Condition>) -> Unit,
    pathField: ConditionPathField,
    enabled: Boolean,
    ops: List<ConditionOp> = ConditionOp.entries,
    newCondition: () -> Condition = { Condition("", ConditionOp.EQUALS, value = "") },
    pathLabel: String = "Field",
    addLabel: String = "Add condition",
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        conditions.forEachIndexed { index, condition ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Condition ${index + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onConditionsChanged(conditions.filterIndexed { i, _ -> i != index }) }, enabled = enabled) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove condition")
                        }
                    }
                    ConditionRow(
                        condition = condition,
                        onConditionChanged = { updated ->
                            onConditionsChanged(
                                conditions.mapIndexed { i, c ->
                                    if (i ==
                                        index
                                    ) {
                                        updated
                                    } else {
                                        c
                                    }
                                },
                            )
                        },
                        pathField = pathField,
                        enabled = enabled,
                        ops = ops,
                        pathLabel = pathLabel,
                    )
                }
            }
        }
        TextButton(onClick = { onConditionsChanged(conditions + newCondition()) }, enabled = enabled) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text(addLabel)
        }
    }
}

/** A single editable [Condition]: path, op, and the op's operand (a value, or another path). */
@Composable
fun ConditionRow(
    condition: Condition,
    onConditionChanged: (Condition) -> Unit,
    pathField: ConditionPathField,
    enabled: Boolean,
    ops: List<ConditionOp> = ConditionOp.entries,
    pathLabel: String = "Field",
) {
    pathField.Field(pathLabel, condition.path, { onConditionChanged(condition.copy(path = it)) }, condition.path.isBlank())
    Spacer(modifier = Modifier.height(4.dp))
    ConditionOpDropdown(selected = condition.op, onSelected = { onConditionChanged(condition.withOp(it)) }, ops = ops, enabled = enabled)
    when (condition.op.operand) {
        ConditionOperand.VALUE -> {
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = condition.value.orEmpty(),
                onValueChange = { onConditionChanged(condition.copy(value = it)) },
                label = { Text(condition.op.operandLabel()) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled,
                isError = condition.value.isNullOrBlank(),
            )
        }
        ConditionOperand.OTHER_PATH -> {
            Spacer(modifier = Modifier.height(4.dp))
            pathField.Field("Other ${pathLabel.lowercase()}", condition.otherPath.orEmpty(), {
                onConditionChanged(condition.copy(otherPath = it))
            }, condition.otherPath.isNullOrBlank())
        }
        ConditionOperand.NONE -> Unit
    }
}

@Composable
private fun ConditionOpDropdown(
    selected: ConditionOp,
    onSelected: (ConditionOp) -> Unit,
    ops: List<ConditionOp>,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = !expanded }) {
        OutlinedTextField(
            value = selected.label(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Operator") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            enabled = enabled,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ops.forEach { op ->
                DropdownMenuItem(
                    text = { Text(op.label()) },
                    onClick = {
                        onSelected(op)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** A short human label for [this] op, as shown in the operator dropdown. */
fun ConditionOp.label(): String =
    when (this) {
        ConditionOp.EXISTS -> "exists"
        ConditionOp.BLANK -> "is blank"
        ConditionOp.NOT_BLANK -> "is not blank"
        ConditionOp.EQUALS -> "equals value"
        ConditionOp.NOT_EQUALS -> "not equals value"
        ConditionOp.EQUALS_IGNORE_CASE -> "equals value (ignore case)"
        ConditionOp.IN -> "is one of"
        ConditionOp.NOT_IN -> "is none of"
        ConditionOp.STARTS_WITH -> "starts with"
        ConditionOp.MATCHES -> "matches regex"
        ConditionOp.EQUALS_PATH -> "equals other field"
        ConditionOp.NOT_EQUALS_PATH -> "not equals other field"
        ConditionOp.ANY_ELEMENT_STARTS_WITH -> "has an element starting with"
        ConditionOp.EMPTY_OBJECT -> "is an empty object"
        ConditionOp.NON_EMPTY_OBJECT -> "is a non-empty object"
    }

private fun ConditionOp.operandLabel(): String =
    when (this) {
        ConditionOp.IN, ConditionOp.NOT_IN -> "Values (comma-separated)"
        ConditionOp.MATCHES -> "Pattern (regex, case-insensitive)"
        else -> "Value"
    }
