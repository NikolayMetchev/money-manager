package com.moneymanager.csvimporter

import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.ImportFileKind
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createCsvImport
import com.moneymanager.importengineapi.createXlsxImport
import com.moneymanager.xlsx.createXlsxParser
import kotlin.time.Instant

/**
 * The checksum a file of [kind] is staged under, which tells whether it was staged before: a text file's
 * decoded content, a binary file's bytes.
 */
suspend fun importFileChecksum(
    kind: ImportFileKind,
    bytes: ByteArray,
): String = if (kind.isBinary) sha256Hex(bytes) else sha256Hex(bytes.decodeToString())

/**
 * Stages [bytes] as a CSV import the way [kind] is read: a CSV as is, a QIF from its records ([stageQifImport]),
 * an Excel workbook from its first worksheet, a PDF from its text lines ([stagePdfImport]). A file whose
 * [checksum] is already staged isn't staged again; its existing import is returned. The one way a file is
 * staged, from an import folder or a picked file. Excel and PDF parsing throw where the platform can't read them.
 */
@Suppress("LongParameterList")
suspend fun ImportEngine.stageImportFile(
    kind: ImportFileKind,
    fileName: String,
    bytes: ByteArray,
    checksum: String,
    lastModified: Instant,
    csvImportRepository: CsvImportReadRepository,
): CsvImportId =
    csvImportRepository.findImportsByChecksum(checksum).firstOrNull()?.id
        ?: when (kind) {
            ImportFileKind.CSV -> {
                val parsed = parseStagedCsv(bytes.decodeToString())
                createCsvImport(
                    fileName = fileName,
                    headers = parsed.headers,
                    rows = parsed.rows,
                    fileChecksum = checksum,
                    fileLastModified = lastModified,
                )
            }

            ImportFileKind.QIF -> {
                stageQifImport(fileName, bytes.decodeToString(), checksum, lastModified)
            }

            ImportFileKind.XLSX -> {
                stageXlsx(fileName, bytes, checksum, lastModified)
            }

            ImportFileKind.PDF -> {
                stagePdfImport(fileName, bytes, checksum, lastModified)
            }
        }

/**
 * Stages an Excel workbook's first worksheet like a CSV (header row + data rows), plus the raw workbook bytes so
 * a strategy naming a different worksheet can re-extract it later. Throws `XlsxUnsupportedPlatformException` on
 * Android and native (Apache POI is JVM-only).
 */
private suspend fun ImportEngine.stageXlsx(
    fileName: String,
    bytes: ByteArray,
    checksum: String,
    lastModified: Instant,
): CsvImportId {
    val parser = createXlsxParser()
    val sheetName = parser.sheetNames(bytes).firstOrNull() ?: ""
    val parsed = parser.parse(bytes, sheetName)
    return createXlsxImport(
        fileName = fileName,
        headers = parsed.headers,
        rows = parsed.rows,
        fileChecksum = checksum,
        fileLastModified = lastModified,
        xlsxBytes = bytes,
        xlsxWorksheetName = sheetName,
    )
}
