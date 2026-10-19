package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.ui.components.rules.RuleContext
import com.moneymanager.ui.components.rules.StrategyRulesEditor

/**
 * Rules tab: the strategy's if/then rules, which decide everything about a row the field mappings don't —
 * its accounts, direction, attributes, exclusion, fee, foreign amount, conduit and leg group.
 */
@Composable
internal fun RulesTab(
    state: CsvStrategyEditorState,
    csvColumns: List<CsvColumn>,
    firstRow: CsvRow?,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Each rule whose conditions all hold applies its actions to the row. Some rule must name the target " +
                "account; a row no rule gives a direction is money out of the account.",
            style = MaterialTheme.typography.bodySmall,
            color = if (state.rulesHaveError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        StrategyRulesEditor(
            rules = state.rules,
            onRulesChanged = { state.rules = it },
            context = RuleContext.CSV_ROW,
            pathField = columnPathField(csvColumns, firstRow, enabled),
            enabled = enabled,
        )
    }
}
