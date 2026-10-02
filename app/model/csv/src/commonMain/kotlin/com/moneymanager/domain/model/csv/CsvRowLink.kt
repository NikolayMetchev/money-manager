package com.moneymanager.domain.model.csv

import com.moneymanager.domain.model.TransferId

/**
 * A staged row's import outcome carried onto its counterpart when a file is re-staged in place (see
 * `CsvImportWriteRepository.repopulateImport`): the row at [rowIndex] of the new staging keeps the
 * [status] and transaction link its matching old row had.
 */
data class CsvRowLink(
    val rowIndex: Long,
    val status: ImportStatus,
    val transferId: TransferId?,
)
