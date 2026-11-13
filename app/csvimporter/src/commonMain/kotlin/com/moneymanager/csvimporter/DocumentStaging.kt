package com.moneymanager.csvimporter

import com.moneymanager.csv.CsvParseResult
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.ImportFileKind
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createCsvImport
import kotlin.time.Instant

/**
 * A document format's file [bytes] as the CSV rows it is staged as, over the format's fixed columns: a QIF file's
 * transactions ([parseQifAsCsv]) or an OFX file's ([parseOfxAsCsv]). Null for a [kind] that isn't a document.
 */
fun parseDocumentAsCsv(
    kind: ImportFileKind,
    bytes: ByteArray,
): CsvParseResult? =
    when (kind) {
        ImportFileKind.QIF -> parseQifAsCsv(bytes.decodeToString())
        ImportFileKind.OFX -> parseOfxAsCsv(bytes)
        ImportFileKind.CSV, ImportFileKind.XLSX, ImportFileKind.PDF -> null
    }

/**
 * Stages a document format's file [bytes] as a CSV import of its rows ([parseDocumentAsCsv]): the one way a QIF or
 * OFX file is staged, from an import folder or a picked file. From then on it is a CSV import like any other.
 */
suspend fun ImportEngine.stageDocumentImport(
    kind: ImportFileKind,
    fileName: String,
    bytes: ByteArray,
    fileChecksum: String,
    fileLastModified: Instant,
): CsvImportId {
    val parsed = requireNotNull(parseDocumentAsCsv(kind, bytes)) { "$kind is not a document format" }
    return createCsvImport(
        fileName = fileName,
        headers = parsed.headers,
        rows = parsed.rows,
        fileChecksum = fileChecksum,
        fileLastModified = fileLastModified,
    )
}
