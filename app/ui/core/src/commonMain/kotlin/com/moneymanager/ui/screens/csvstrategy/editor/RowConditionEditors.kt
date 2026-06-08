@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csvstrategy.RowCondition
import com.moneymanager.domain.model.csvstrategy.RowConditionOperator

/**
 * Editor for a list of [RowCondition]s combined with AND semantics.
 */
@Composable
internal fun RowConditionsEditor(
    conditions: List<RowCondition>,
    onConditionsChanged: (List<RowCondition>) -> Unit,
    columns: List<CsvColumn>,
    enabled: Boolean,
    title: String = "Conditions (all must match)",
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        conditions.forEachIndexed { index, condition ->
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
                            "Condition ${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onConditionsChanged(conditions.filterIndexed { i, _ -> i != index }) },
                            enabled = enabled,
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove condition")
                        }
                    }
                    RowConditionRow(
                        condition = condition,
                        onConditionChanged = { updated ->
                            onConditionsChanged(conditions.mapIndexed { i, c -> if (i == index) updated else c })
                        },
                        columns = columns,
                        enabled = enabled,
                    )
                }
            }
        }
        TextButton(
            onClick = {
                onConditionsChanged(
                    conditions +
                        RowCondition(
                            columnName = columns.firstOrNull()?.originalName.orEmpty(),
                            operator = RowConditionOperator.EQUALS_VALUE,
                            value = "",
                        ),
                )
            },
            enabled = enabled,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text("Add Condition")
        }
    }
}

/**
 * A single editable [RowCondition]: column, operator, and the operator-dependent
 * value or other-column input.
 */
@Composable
internal fun RowConditionRow(
    condition: RowCondition,
    onConditionChanged: (RowCondition) -> Unit,
    columns: List<CsvColumn>,
    enabled: Boolean,
) {
    ColumnDropdown(
        columns = columns,
        selectedColumn = condition.columnName.takeIf { it.isNotBlank() },
        onColumnSelected = { onConditionChanged(condition.copy(columnName = it)) },
        label = "Column",
        enabled = enabled,
        isError = condition.columnName.isBlank(),
    )
    Spacer(modifier = Modifier.height(4.dp))
    OperatorDropdown(
        selected = condition.operator,
        onSelected = { op ->
            onConditionChanged(
                when (op) {
                    RowConditionOperator.EQUALS_VALUE ->
                        condition.copy(operator = op, otherColumnName = null, value = condition.value ?: "")
                    RowConditionOperator.EQUALS_COLUMN, RowConditionOperator.NOT_EQUALS_COLUMN ->
                        condition.copy(operator = op, value = null)
                    RowConditionOperator.IS_BLANK, RowConditionOperator.IS_NOT_BLANK ->
                        condition.copy(operator = op, value = null, otherColumnName = null)
                },
            )
        },
        enabled = enabled,
    )
    when (condition.operator) {
        RowConditionOperator.EQUALS_VALUE -> {
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = condition.value.orEmpty(),
                onValueChange = { onConditionChanged(condition.copy(value = it)) },
                label = { Text("Value") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled,
                isError = condition.value.isNullOrBlank(),
            )
        }
        RowConditionOperator.EQUALS_COLUMN, RowConditionOperator.NOT_EQUALS_COLUMN -> {
            Spacer(modifier = Modifier.height(4.dp))
            ColumnDropdown(
                columns = columns,
                selectedColumn = condition.otherColumnName,
                onColumnSelected = { onConditionChanged(condition.copy(otherColumnName = it)) },
                label = "Other column",
                enabled = enabled,
                isError = condition.otherColumnName.isNullOrBlank(),
            )
        }
        RowConditionOperator.IS_BLANK, RowConditionOperator.IS_NOT_BLANK -> Unit
    }
}

/**
 * Dropdown selecting a [RowConditionOperator].
 */
@Composable
internal fun OperatorDropdown(
    selected: RowConditionOperator,
    onSelected: (RowConditionOperator) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selected.label(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Operator") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            enabled = enabled,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            RowConditionOperator.entries.forEach { op ->
                DropdownMenuItem(
                    text = { DropdownSelectionRow(text = op.label(), selected = op == selected) },
                    onClick = {
                        onSelected(op)
                        expanded = false
                    },
                )
            }
        }
    }
}
