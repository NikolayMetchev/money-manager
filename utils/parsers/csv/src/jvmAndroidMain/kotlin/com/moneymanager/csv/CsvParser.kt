package com.moneymanager.csv

/**
 * JVM/Android implementation of CSV parser.
 */
actual class CsvParser actual constructor() {
    actual fun parse(
        content: String,
        options: CsvParseOptions,
    ): CsvParseResult {
        if (content.isBlank()) {
            return CsvParseResult(headers = emptyList(), rows = emptyList())
        }

        val lines = parseLines(content, options)
        if (lines.isEmpty()) {
            return CsvParseResult(headers = emptyList(), rows = emptyList())
        }

        return if (options.hasHeaders) {
            val headers = lines.firstOrNull() ?: emptyList()
            val rows =
                if (lines.size > 1) {
                    normalizeRows(lines.drop(1), headers.size)
                } else {
                    emptyList()
                }
            CsvParseResult(headers = headers, rows = rows)
        } else {
            val maxColumns = lines.maxOfOrNull { it.size } ?: 0
            val rows = normalizeRows(lines, maxColumns)
            CsvParseResult(headers = emptyList(), rows = rows)
        }
    }

    actual fun detectDelimiter(content: String): Char {
        val candidates = listOf(',', ';', '\t', '|')
        val lines = content.lines().take(LINES_TO_ANALYZE).filter { it.isNotBlank() }

        if (lines.isEmpty()) {
            return ','
        }

        val scores =
            candidates.associateWith { delimiter ->
                calculateDelimiterScore(lines, delimiter)
            }

        return scores.maxByOrNull { it.value }?.key ?: ','
    }

    private fun parseLines(
        content: String,
        options: CsvParseOptions,
    ): List<List<String>> {
        val result = mutableListOf<List<String>>()
        val currentField = StringBuilder()
        val currentRow = mutableListOf<String>()
        var inQuotes = false
        var i = 0

        while (i < content.length) {
            val char = content[i]
            val nextChar = content.getOrNull(i + 1)

            when {
                // Handle quoted field
                char == options.quoteChar && !inQuotes -> {
                    inQuotes = true
                }
                // Handle escaped quote (two consecutive quotes inside quoted field)
                char == options.quoteChar && inQuotes && nextChar == options.quoteChar -> {
                    currentField.append(options.quoteChar)
                    i++ // Skip the next quote
                }
                // Handle end of quoted field
                char == options.quoteChar && inQuotes -> {
                    inQuotes = false
                }
                // Handle delimiter outside quotes
                char == options.delimiter && !inQuotes -> {
                    currentRow.add(currentField.toString())
                    currentField.clear()
                }
                // Handle newline outside quotes (end of row)
                (char == '\n' || (char == '\r' && nextChar == '\n')) && !inQuotes -> {
                    currentRow.add(currentField.toString())
                    currentField.clear()
                    if (currentRow.isNotEmpty()) {
                        result.add(currentRow.toList())
                    }
                    currentRow.clear()
                    if (char == '\r' && nextChar == '\n') {
                        i++ // Skip the \n in CRLF
                    }
                }
                // Handle standalone CR outside quotes (old Mac format)
                char == '\r' && nextChar != '\n' && !inQuotes -> {
                    currentRow.add(currentField.toString())
                    currentField.clear()
                    if (currentRow.isNotEmpty()) {
                        result.add(currentRow.toList())
                    }
                    currentRow.clear()
                }
                // Regular character (including newlines inside quotes)
                else -> {
                    currentField.append(char)
                }
            }
            i++
        }

        // Handle last field/row
        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentField.toString())
            if (currentRow.isNotEmpty()) {
                result.add(currentRow.toList())
            }
        }

        return result
    }

    private fun normalizeRows(
        rows: List<List<String>>,
        columnCount: Int,
    ): List<List<String>> {
        return rows.map { row ->
            when {
                row.size < columnCount -> row + List(columnCount - row.size) { "" }
                row.size > columnCount -> row.take(columnCount)
                else -> row
            }
        }
    }

    private fun calculateDelimiterScore(
        lines: List<String>,
        delimiter: Char,
    ): Int {
        val counts =
            lines.map { line ->
                countDelimiterOccurrences(line, delimiter)
            }

        // Check if counts are consistent across lines
        if (counts.isEmpty() || counts.all { it == 0 }) {
            return 0
        }

        val firstCount = counts.first()
        val isConsistent = counts.all { it == firstCount }

        return if (isConsistent && firstCount > 0) {
            // Higher score for consistent delimiter counts
            firstCount * CONSISTENCY_MULTIPLIER
        } else {
            // Lower score for inconsistent counts
            counts.sum()
        }
    }

    private fun countDelimiterOccurrences(
        line: String,
        delimiter: Char,
    ): Int {
        var count = 0
        var inQuotes = false

        for (char in line) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == delimiter && !inQuotes -> count++
            }
        }

        return count
    }

    companion object {
        private const val LINES_TO_ANALYZE = 5
        private const val CONSISTENCY_MULTIPLIER = 10
    }
}
