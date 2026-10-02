package com.moneymanager.csv

/**
 * CSV parser that handles quoted fields, escaped quotes, and various delimiters.
 */
class CsvParser {
    /**
     * Parses CSV content into headers and rows.
     *
     * @param content The CSV content as a string
     * @param options Parsing configuration options
     * @return Parsed result with headers and rows
     */
    fun parse(
        content: String,
        options: CsvParseOptions = CsvParseOptions(),
    ): CsvParseResult {
        if (content.isBlank()) {
            return CsvParseResult(headers = emptyList(), rows = emptyList())
        }

        val parsedLines = parseLines(content, options)
        val lines = if (options.hasHeaders) parsedLines.drop(preambleLength(parsedLines)) else parsedLines
        if (lines.isEmpty()) {
            return CsvParseResult(headers = emptyList(), rows = emptyList())
        }

        return if (options.hasHeaders) {
            val headers = lines.firstOrNull().orEmpty()
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

    /**
     * Detects the most likely delimiter used in the CSV content.
     * Analyzes the first few lines to determine the delimiter.
     *
     * @param content The CSV content (or first few lines) to analyze
     * @return The detected delimiter character
     */
    fun detectDelimiter(content: String): Char {
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

            when (char) {
                options.quoteChar -> {
                    if (inQuotes && nextChar == options.quoteChar) {
                        currentField.append(options.quoteChar)
                        i++ // Skip the next quote
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                // Handle delimiter outside quotes
                options.delimiter ->
                    if (!inQuotes) {
                        currentRow.add(currentField.toString())
                        currentField.clear()
                    } else {
                        currentField.append(char)
                    }
                // Handle newline outside quotes (end of row)
                '\n' ->
                    if (!inQuotes) {
                        currentRow.add(currentField.toString())
                        currentField.clear()
                        if (currentRow.isNotEmpty()) {
                            result.add(currentRow.toList())
                        }
                        currentRow.clear()
                    } else {
                        currentField.append(char)
                    }
                // Handle CR outside quotes (CRLF or old Mac format)
                '\r' ->
                    if (!inQuotes) {
                        currentRow.add(currentField.toString())
                        currentField.clear()
                        if (currentRow.isNotEmpty()) {
                            result.add(currentRow.toList())
                        }
                        currentRow.clear()
                        if (nextChar == '\n') {
                            i++ // Skip the \n in CRLF
                        }
                    } else {
                        currentField.append(char)
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

    /**
     * How many leading rows are a preamble above the real header. Some exports (Bybit's statements) open
     * with a narrower summary line such as `UID: 123,Company Name: ,Country: ` that would otherwise be read
     * as the header, mis-sizing every column. A row only counts as preamble when it is narrower than every
     * row after it (an ordinary header with one over-wide data row below is not), and the row after it looks
     * like a header: no blank cells, and at least as wide as each of the rows that follow it. The blank-cell
     * test keeps an ordinary file whose data rows carry a trailing delimiter (one cell wider than the
     * header, that cell empty) from losing its real header.
     */
    private fun preambleLength(lines: List<List<String>>): Int {
        var skipped = 0
        while (skipped < MAX_PREAMBLE_ROWS && skipped + 1 < lines.size) {
            val row = lines[skipped]
            val header = lines[skipped + 1]
            val following = lines.drop(skipped + 2).take(LINES_TO_ANALYZE)
            val looksLikeHeader =
                header.none { it.isBlank() } && following.all { it.size <= header.size }
            val narrowerThanAllBelow = row.size < header.size && following.all { row.size < it.size }
            if (!narrowerThanAllBelow || !looksLikeHeader) break
            skipped++
        }
        return skipped
    }

    private fun normalizeRows(
        rows: List<List<String>>,
        columnCount: Int,
    ): List<List<String>> =
        rows.map { row ->
            when {
                row.size < columnCount -> row + List(columnCount - row.size) { "" }
                row.size > columnCount -> row.take(columnCount)
                else -> row
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
            when (char) {
                '"' -> inQuotes = !inQuotes
                delimiter -> if (!inQuotes) count++
            }
        }

        return count
    }

    companion object {
        private const val LINES_TO_ANALYZE = 5
        private const val MAX_PREAMBLE_ROWS = 3
        private const val CONSISTENCY_MULTIPLIER = 10
    }
}
