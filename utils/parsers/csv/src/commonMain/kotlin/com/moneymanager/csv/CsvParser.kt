package com.moneymanager.csv

/**
 * Platform-independent CSV parser.
 * Handles quoted fields, escaped quotes, and various delimiters.
 */
expect class CsvParser() {
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
    ): CsvParseResult

    /**
     * Detects the most likely delimiter used in the CSV content.
     * Analyzes the first few lines to determine the delimiter.
     *
     * @param content The CSV content (or first few lines) to analyze
     * @return The detected delimiter character
     */
    fun detectDelimiter(content: String): Char
}
