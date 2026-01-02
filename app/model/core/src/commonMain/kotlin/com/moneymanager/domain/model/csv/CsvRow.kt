@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model.csv

import com.moneymanager.domain.model.TransferId

/**
 * Represents a row of data from a CSV import.
 *
 * @property rowIndex The 1-based row index from the original CSV file
 * @property values The column values, indexed by column position (0-based)
 * @property transferId The ID of the transfer created from this row, null if not yet imported
 * @property importStatus The import status of this row (IMPORTED, DUPLICATE, UPDATED), null if not yet processed
 */
data class CsvRow(
    val rowIndex: Long,
    val values: List<String>,
    val transferId: TransferId? = null,
    val importStatus: ImportStatus? = null,
)
