package com.moneymanager.domain.model

import kotlinx.serialization.Serializable

/**
 * The file formats Money Manager can stage, told apart by extension. Every one is staged as a CSV import (a QIF
 * or OFX file's transactions, an Excel file's first worksheet, a PDF's text lines), so this only says how a file is read.
 */
@Serializable
enum class ImportFileKind(
    val label: String,
) {
    CSV("CSV"),
    QIF("QIF"),
    OFX("OFX"),
    XLSX("Excel"),
    PDF("PDF"),
    ;

    /** Excel and PDF are read as bytes; decoding them as text would corrupt them. */
    val isBinary: Boolean get() = this == XLSX || this == PDF

    /**
     * QIF and OFX are documents: parsed into a fixed column set (one row per transaction) and matched to a strategy
     * as a whole, since their columns say nothing of the institution and any one row may be the one that does.
     */
    val isDocument: Boolean get() = this == QIF || this == OFX
}

/** The kind of [fileName] from its extension, or null for a file Money Manager can't stage. */
fun importFileKind(fileName: String): ImportFileKind? =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "csv" -> ImportFileKind.CSV
        "qif" -> ImportFileKind.QIF
        "ofx" -> ImportFileKind.OFX
        "xlsx" -> ImportFileKind.XLSX
        "pdf" -> ImportFileKind.PDF
        else -> null
    }
