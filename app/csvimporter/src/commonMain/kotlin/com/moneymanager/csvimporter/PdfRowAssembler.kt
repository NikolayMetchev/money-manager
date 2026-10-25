package com.moneymanager.csvimporter

import com.moneymanager.domain.model.csv.PdfTextLine
import com.moneymanager.domain.model.csvstrategy.PdfLineConfig
import com.moneymanager.domain.model.rules.CaptureTarget
import com.moneymanager.domain.model.rules.Record
import com.moneymanager.domain.model.rules.RuleEngine
import kotlinx.serialization.json.Json

/** A PDF's staged table: [headers] and one row of [rows] per assembled row (or per line, before any strategy). */
data class PdfStagedRows(
    val headers: List<String>,
    val rows: List<List<String>>,
)

/**
 * Turns a PDF's text lines into the rows a strategy reads, by running its [PdfLineConfig.rules] over each line in
 * reading order. A line's [com.moneymanager.domain.model.rules.RuleAction.Capture] either starts a row, adds to
 * the row in progress or records a value of the whole document, which every row then carries where it has none
 * of its own. A line nothing captures from is dropped, as is a value captured for a row before any row started.
 */
class PdfRowAssembler(
    private val config: PdfLineConfig,
) {
    private val engine = RuleEngine()

    fun assemble(lines: List<PdfTextLine>): PdfStagedRows {
        val columns = config.columns()
        val document = LinkedHashMap<String, String>()
        val rows = mutableListOf<Map<String, String>>()
        var current: MutableMap<String, String>? = null
        for (line in lines) {
            val base =
                mapOf(
                    PdfLineConfig.PAGE to line.page.toString(),
                    PdfLineConfig.LINE to line.line.toString(),
                    PdfLineConfig.TEXT to line.text,
                )
            val outcome = engine.evaluate(config.rules, LineRecord(base, document))
            if (outcome.skipped) continue
            val captured =
                outcome.rewrites
                    .filterKeys { it !in PdfLineConfig.BASE_COLUMNS }
                    .mapNotNull { (path, value) -> value?.let { path to it } }
            when (outcome.capture) {
                CaptureTarget.NEW_ROW -> current = LinkedHashMap(base + captured).also(rows::add)
                CaptureTarget.ROW -> current?.putAll(captured)
                CaptureTarget.DOCUMENT -> captured.forEach { (path, value) -> document.getOrPut(path) { value } }
                null -> Unit
            }
        }
        return PdfStagedRows(columns, rows.map { row -> columns.map { row[it] ?: document[it].orEmpty() } })
    }

    // A line as its rules see it: page, line and text, then the document's values so far.
    private class LineRecord(
        private val line: Map<String, String>,
        private val document: Map<String, String>,
    ) : Record {
        override fun exists(path: String): Boolean = path in line || path in document

        override fun text(path: String): String? = line[path] ?: document[path]
    }

    companion object {
        /** A PDF staged before any strategy reads it: one row per line, its page, line number and text. */
        fun rawLines(lines: List<PdfTextLine>): PdfStagedRows =
            PdfStagedRows(PdfLineConfig.BASE_COLUMNS, lines.map { listOf(it.page.toString(), it.line.toString(), it.text) })

        /** Names [config]'s line rules, so a PDF staged with them isn't assembled again until they change. */
        suspend fun rulesHash(config: PdfLineConfig): String = sha256Hex(Json.encodeToString(PdfLineConfig.serializer(), config))
    }
}
