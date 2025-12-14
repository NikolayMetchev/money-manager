package com.moneymanager.domain.model.csv

/**
 * Represents a row of data from a CSV import.
 *
 * @property rowId The database row ID (auto-generated)
 * @property values The column values, indexed by column position (0-based)
 */
data class CsvRow(
    val rowId: Long,
    val values: List<String>,
)
