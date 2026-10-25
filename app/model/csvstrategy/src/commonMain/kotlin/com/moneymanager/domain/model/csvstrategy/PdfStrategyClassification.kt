package com.moneymanager.domain.model.csvstrategy

/**
 * Whether this strategy reads PDF statements rather than CSV files. Like QIF and XLSX, a PDF strategy is
 * an ordinary [CsvImportStrategy] row (it rides the CSV engine); its line rules
 * ([CsvStrategyConfig.pdfLines]) being set is the only signal.
 */
fun CsvImportStrategy.isPdfStrategy(): Boolean = config.pdfLines != null
