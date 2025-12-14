package com.moneymanager.csv

/**
 * Result of parsing a CSV file.
 *
 * @property headers The column headers (empty if hasHeaders was false)
 * @property rows The data rows, each as a list of string values
 */
data class CsvParseResult(
    val headers: List<String>,
    val rows: List<List<String>>,
)
