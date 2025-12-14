package com.moneymanager.csv

/**
 * Configuration options for CSV parsing.
 *
 * @property delimiter The character used to separate fields (default: comma)
 * @property hasHeaders Whether the first row contains column headers (default: true)
 * @property quoteChar The character used to quote fields containing special characters (default: double quote)
 */
data class CsvParseOptions(
    val delimiter: Char = ',',
    val hasHeaders: Boolean = true,
    val quoteChar: Char = '"',
)
