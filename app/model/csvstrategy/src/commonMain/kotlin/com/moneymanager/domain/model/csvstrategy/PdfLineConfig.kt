package com.moneymanager.domain.model.csvstrategy

import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.captureGroupNames
import kotlinx.serialization.Serializable

/**
 * How a PDF's text becomes rows (see [CsvStrategyConfig.pdfLines]). [rules] run over each line of the document
 * in reading order, as a record of [PAGE], [LINE] and [TEXT] (plus the document's values captured so far); a
 * rule's [RuleAction.Capture] takes values out of the line and says whether it starts a row, continues one or
 * describes the whole document. A line no rule captures from is dropped.
 */
@Serializable
data class PdfLineConfig(
    // Run in order and first-claim-wins like every rule list: never sorted.
    val rules: List<StrategyRule> = emptyList(),
) {
    /**
     * The columns of the rows the rules assemble: [PAGE], [LINE] and [TEXT] of the row's first line, then
     * every value a rule captures or sets, in rule order. They come from the rules, not from a document, so
     * every document a strategy reads has the same columns.
     */
    fun columns(): List<String> =
        (
            BASE_COLUMNS +
                rules.flatMap { rule ->
                    rule.actions.flatMap { action ->
                        when (action) {
                            is RuleAction.Capture -> captureGroupNames(action.pattern)
                            is RuleAction.SetValue -> listOf(action.path)
                            else -> emptyList()
                        }
                    }
                }
        ).distinct()

    companion object {
        /** The 1-based page a line is on. */
        const val PAGE: String = "page"

        /** The line's 1-based number on its page. */
        const val LINE: String = "line"

        /** The line's text, columns kept apart by runs of spaces. */
        const val TEXT: String = "text"

        /** The columns of a PDF import before a strategy assembles its rows: one row per line. */
        val BASE_COLUMNS: List<String> = listOf(PAGE, LINE, TEXT)
    }
}

/** Whether this strategy reads PDFs: its [CsvStrategyConfig.pdfLines] turn a document's lines into rows. */
fun CsvImportStrategy.isPdfStrategy(): Boolean = config.pdfLines != null
