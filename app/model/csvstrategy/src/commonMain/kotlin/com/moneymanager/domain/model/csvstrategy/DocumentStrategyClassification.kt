package com.moneymanager.domain.model.csvstrategy

import com.moneymanager.domain.model.ImportFileKind
import com.moneymanager.domain.model.ofx.OfxColumns
import com.moneymanager.domain.model.qif.QifColumns

/**
 * The document format ([ImportFileKind.isDocument]) this strategy targets, or null for one that reads a CSV, Excel or
 * PDF file. Document strategies are ordinary [CsvImportStrategy] rows (QIF and OFX ride the CSV engine), told apart
 * only by their identification columns being drawn entirely from the format's fixed column set ([QifColumns.headers],
 * [OfxColumns.headers]).
 *
 * Exact column-set equality is too brittle (a user may identify on a subset), so this uses a subset check; that also
 * excludes real CSV strategies (Wise/Monzo) whose columns aren't a subset of either. Centralized here so the DB,
 * importer and UI layers all classify strategies identically.
 */
fun CsvImportStrategy.documentKind(): ImportFileKind? {
    val columns = config.identificationColumns
    return when {
        columns.isEmpty() -> null
        columns.all { it in QifColumns.headers } -> ImportFileKind.QIF
        columns.all { it in OfxColumns.headers } -> ImportFileKind.OFX
        else -> null
    }
}
