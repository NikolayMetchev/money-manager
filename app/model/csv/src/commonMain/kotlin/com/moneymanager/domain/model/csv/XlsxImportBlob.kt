package com.moneymanager.domain.model.csv

/**
 * The raw bytes of an Excel workbook staged as a [CsvImport], kept alongside its staged rows so the
 * worksheet can be re-extracted if the matched strategy names a different sheet than the one initially
 * staged. Absent for CSV/QIF imports.
 */
data class XlsxImportBlob(
    val fileBytes: ByteArray,
    val worksheetName: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is XlsxImportBlob &&
                    fileBytes.contentEquals(other.fileBytes) &&
                    worksheetName == other.worksheetName
            )

    override fun hashCode(): Int = 31 * fileBytes.contentHashCode() + worksheetName.hashCode()
}
