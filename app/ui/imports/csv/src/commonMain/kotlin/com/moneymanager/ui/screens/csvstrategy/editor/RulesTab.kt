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
    // The rows the line rules assemble from the PDF being edited against; null for a strategy over a CSV, QIF or
    // Excel file.
    pdfPreview: PdfLinesPreview? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        state.pdfLineRules?.let { lineRules ->
            Text("PDF lines", style = MaterialTheme.typography.titleSmall)
            Text(
                "These rules run over each line of the statement's text first. A Capture rule's named groups " +
                    "become columns: a line that starts a new row, one that continues it, or one holding a value " +
                    "every row gets. The rows they build are what the field mappings and the rules below read.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            StrategyRulesEditor(
                rules = lineRules,
                onRulesChanged = { state.pdfLineRules = it },
                context = RuleContext.PDF_LINE,
                pathField = columnPathField(csvColumns, firstRow, enabled),
                enabled = enabled,
            )
            pdfPreview?.let {
                Spacer(modifier = Modifier.height(8.dp))
                PdfLinesPreviewTable(it)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Rows", style = MaterialTheme.typography.titleSmall)
        }
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
