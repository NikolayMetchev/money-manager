package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.ui.components.rules.ConditionPathField
import com.moneymanager.ui.components.rules.StrategyRulesEditor
import com.moneymanager.ui.screens.apistrategy.JsonPathEntry

/**
 * Rules tab: every rule list the strategy carries, one section each, which decide everything about a record the
 * other tabs' field mappings don't. The CSV/QIF editor's Rules tab is the same editor over its one list.
 */
@Composable
internal fun RulesTab(
    state: ApiStrategyEditorState,
    accountJsonPaths: List<JsonPathEntry>,
    txJsonPaths: List<JsonPathEntry>,
    onRequestPick: PathPicker,
    enabled: Boolean,
) {
    val ruleLists = state.config.ruleLists()
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ruleLists.isEmpty()) {
            Text("This strategy has no endpoints yet: add one on the Endpoints tab.", style = MaterialTheme.typography.bodyMedium)
        }
        ruleLists.forEachIndexed { index, list ->
            if (index > 0) HorizontalDivider()
            SectionHeader(list.title)
            StrategyRulesEditor(
                rules = list.rules,
                onRulesChanged = { rules -> state.updateConfig { list.withRules(this, rules) } },
                context = list.context,
                pathField =
                    when (list.paths) {
                        RulePathSource.ACCOUNT_SAMPLE -> jsonPathField(accountJsonPaths, onRequestPick, enabled)
                        RulePathSource.TRANSACTION_SAMPLE -> jsonPathField(txJsonPaths, onRequestPick, enabled)
                        RulePathSource.NONE -> textPathField(enabled)
                    },
                enabled = enabled,
                title = "Rules",
            )
        }
    }
}

/** A plain text field for a JSON path, for lists with no session sample to pick from. */
internal fun textPathField(enabled: Boolean) =
    ConditionPathField { label, value, onValueChange, isError -> TextFieldRow(label, value, onValueChange, enabled, isError = isError) }
