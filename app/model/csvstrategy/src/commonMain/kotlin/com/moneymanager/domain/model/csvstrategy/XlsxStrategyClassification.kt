package com.moneymanager.domain.model.csvstrategy

/**
 * Whether this strategy targets an Excel worksheet rather than a CSV file. Like QIF, an XLSX strategy
 * is an ordinary [CsvImportStrategy] row (it rides the CSV engine); [worksheetName] being set is the
 * only signal, since it has no meaning for a real CSV/QIF strategy.
 */
fun CsvImportStrategy.isXlsxStrategy(): Boolean = worksheetName != null
