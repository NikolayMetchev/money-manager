package com.moneymanager.domain.model

/**
 * The file formats Money Manager can stage, told apart by extension. Every one is staged as a CSV import (a QIF
 * file's records, an Excel file's first worksheet, a PDF's text lines), so this only says how a file is read.
 */
enum class ImportFileKind(
    val label: String,
) {
    CSV("CSV"),
    QIF("QIF"),
    XLSX("Excel"),
    PDF("PDF"),
    ;

    /** Excel and PDF are read as bytes; decoding them as text would corrupt them. */
    val isBinary: Boolean get() = this == XLSX || this == PDF
}

/** The kind of [fileName] from its extension, or null for a file Money Manager can't stage. */
fun importFileKind(fileName: String): ImportFileKind? =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "csv" -> ImportFileKind.CSV
        "qif" -> ImportFileKind.QIF
        "xlsx" -> ImportFileKind.XLSX
        "pdf" -> ImportFileKind.PDF
        else -> null
    }
