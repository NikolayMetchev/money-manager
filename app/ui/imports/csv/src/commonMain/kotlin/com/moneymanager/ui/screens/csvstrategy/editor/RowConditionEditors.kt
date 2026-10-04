package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.runtime.Composable
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.ui.components.rules.ConditionPathField
import com.moneymanager.ui.components.rules.ConditionsEditor
import com.moneymanager.ui.components.rules.FLAT_CONDITION_OPS
import com.moneymanager.ui.screens.csvstrategy.getSampleValue

/** Picks a condition's path among the file's columns, previewing the first row's value. */
private fun columnPathField(
    columns: List<CsvColumn>,
    firstRow: CsvRow?,
    enabled: Boolean,
) = ConditionPathField { label, value, onValueChange, isError ->
    ColumnDropdown(
        columns = columns,
        selectedColumn = value.takeIf { it.isNotBlank() },
        onColumnSelected = onValueChange,
        label = label,
        sampleValue = getSampleValue(columns, firstRow, value),
        enabled = enabled,
        isError = isError,
    )
}

/** Editor for a list of conditions over the row's columns, all of which must hold. */
@Composable
internal fun RowConditionsEditor(
    conditions: List<Condition>,
    onConditionsChanged: (List<Condition>) -> Unit,
    columns: List<CsvColumn>,
    enabled: Boolean,
    title: String = "Conditions (all must match)",
) {
    ConditionsEditor(
        title = title,
        conditions = conditions,
        onConditionsChanged = onConditionsChanged,
        pathField = columnPathField(columns, firstRow = null, enabled = enabled),
        enabled = enabled,
        ops = FLAT_CONDITION_OPS,
        newCondition = { Condition(columns.firstOrNull()?.originalName.orEmpty(), ConditionOp.EQUALS, value = "") },
        pathLabel = "Column",
    )
}

/**
 * Editor for the strategy's content-match conditions, which auto-detect this strategy from a sampled
 * row when the column set is fixed and cannot distinguish formats (QIF): a row matches when ANY holds.
 */
@Composable
internal fun ContentMatchRulesEditor(
    rules: List<Condition>,
    onRulesChanged: (List<Condition>) -> Unit,
    columns: List<CsvColumn>,
    firstRow: CsvRow?,
    enabled: Boolean,
) {
    ConditionsEditor(
        title = "A sampled row matching any of these selects the strategy",
        conditions = rules,
        onConditionsChanged = onRulesChanged,
        pathField = columnPathField(columns, firstRow, enabled),
        enabled = enabled,
        ops = FLAT_CONDITION_OPS,
        newCondition = { Condition("", ConditionOp.MATCHES, value = "") },
        pathLabel = "Column",
        addLabel = "Add Rule",
    )
}
