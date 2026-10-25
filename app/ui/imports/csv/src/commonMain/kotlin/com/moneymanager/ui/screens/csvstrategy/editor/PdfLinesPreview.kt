package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneymanager.csvimporter.PdfRowAssembler
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.PdfTextLine
import com.moneymanager.domain.model.csvstrategy.PdfLineConfig
import com.moneymanager.domain.model.rules.StrategyRule
import kotlin.uuid.Uuid

private const val PREVIEW_ROWS = 20
private val PREVIEW_CELL_WIDTH = 140.dp

/**
 * The rows a PDF strategy's line rules assemble from a PDF import's [lineCount] lines: the [columns] and [rows]
 * every other tab of the editor reads. [error] is why the current rules don't run (a pattern still being typed);
 * the columns and rows are then the last rules' that did.
 */
internal data class PdfLinesPreview(
    val lineCount: Int,
    val columns: List<CsvColumn>,
    val rows: List<CsvRow>,
    val error: String? = null,
)

/** Assembles a PDF import's [lines] with each set of line rules, keeping the last result that ran. */
internal class PdfLinesPreviewer(
    private val lines: List<PdfTextLine>,
) {
    // A column keeps its id while the rules change, so the editor's column pickers stay put.
    private val columnIds = mutableMapOf<String, CsvColumnId>()
    private var lastGood: PdfLinesPreview? = null

    fun preview(rules: List<StrategyRule>): PdfLinesPreview =
        try {
            val staged = PdfRowAssembler(PdfLineConfig(rules)).assemble(lines)
            PdfLinesPreview(
                lineCount = lines.size,
                columns =
                    staged.headers.mapIndexed { index, name ->
                        CsvColumn(columnIds.getOrPut(name) { CsvColumnId(Uuid.random()) }, index, name)
                    },
                rows = staged.rows.mapIndexed { index, values -> CsvRow(rowIndex = index + 1L, values = values) },
            ).also { lastGood = it }
        } catch (expected: IllegalArgumentException) {
            (lastGood ?: PdfLinesPreview(lines.size, emptyList(), emptyList())).copy(error = expected.message ?: "Invalid rule")
        }
}

/** How many rows the line rules assemble, and the first of them, as the rules are edited. */
@Composable
internal fun PdfLinesPreviewTable(preview: PdfLinesPreview) {
    val monospace = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Column {
        Text(
            "${preview.rows.size} rows from ${preview.lineCount} lines",
            style = MaterialTheme.typography.labelLarge,
        )
        preview.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (preview.rows.isNotEmpty()) {
            Column(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp)) {
                Row {
                    preview.columns.forEach { column ->
                        PreviewCell(column.originalName, monospace.copy(fontWeight = FontWeight.Bold))
                    }
                }
                preview.rows.take(PREVIEW_ROWS).forEach { row ->
                    Row {
                        row.values.forEach { PreviewCell(it, monospace) }
                    }
                }
            }
            if (preview.rows.size > PREVIEW_ROWS) {
                Text(
                    "… and ${preview.rows.size - PREVIEW_ROWS} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PreviewCell(
    text: String,
    style: TextStyle,
) {
    Text(
        text,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(PREVIEW_CELL_WIDTH).padding(end = 8.dp),
    )
}
