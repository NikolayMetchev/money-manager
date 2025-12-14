package com.moneymanager.domain.model.csv

/**
 * Represents a row of data from a CSV import.
 *
 * @property rowIndex The 1-based row index from the original CSV file
 * @property values The column values, indexed by column position (0-based)
 */
data class CsvRow(
    val rowIndex: Long,
    val values: List<String>,
)
