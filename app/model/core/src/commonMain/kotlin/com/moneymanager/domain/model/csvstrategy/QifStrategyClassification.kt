package com.moneymanager.domain.model.csvstrategy

import com.moneymanager.domain.model.qif.QifColumns

/**
 * Whether this strategy targets QIF rather than CSV. QIF strategies are ordinary
 * [CsvImportStrategy] rows (QIF rides the CSV engine), distinguished only by their identification
 * columns being drawn entirely from QIF's fixed column set ([QifColumns.headers]).
 *
 * Exact column-set equality is too brittle (a user may identify on a subset), so this uses a subset
 * check; that also excludes real CSV strategies (Wise/Monzo) whose columns aren't a subset of QIF's.
 * Centralized here so the DB, importer and UI layers all classify strategies identically.
 */
fun CsvImportStrategy.isQifStrategy(): Boolean =
    identificationColumns.isNotEmpty() && identificationColumns.all { it in QifColumns.headers }
