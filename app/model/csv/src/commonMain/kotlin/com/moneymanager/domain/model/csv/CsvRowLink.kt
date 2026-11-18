package com.moneymanager.domain.model.csv

/**
 * A staged row's import outcome carried onto its counterpart when a file is re-staged in place (see
 * `CsvImportWriteRepository.repopulateImport`): the row at [rowIndex] of the new staging keeps the
 * [status] its matching old row had. The transaction it stands for follows from its import record.
 */
data class CsvRowLink(
    val rowIndex: Long,
    val status: ImportStatus,
)
