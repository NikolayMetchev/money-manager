package com.moneymanager.ui.screens.csv

import com.moneymanager.compose.filepicker.BinaryFilePickerResult
import com.moneymanager.csvimporter.sha256Hex
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createXlsxImport
import com.moneymanager.xlsx.XlsxParser
import kotlin.time.Clock
import kotlin.time.Instant

/** What staging one picked file reports to the screen. */
internal data class StagedFileMessage(
    val message: String,
    val isError: Boolean,
)

/**
 * Stages a picked Excel or PDF file from its bytes with [stage], unless a file with the same contents is already
 * imported ([isImported], by checksum). A file that fails reports why instead of throwing, including a platform
 * with no PDF or Excel parsing, whose message says so.
 */
internal suspend fun stagePickedFile(
    result: BinaryFilePickerResult,
    isImported: suspend (checksum: String) -> Boolean,
    stage: suspend (checksum: String, lastModified: Instant) -> Unit,
): StagedFileMessage =
    try {
        val checksum = sha256Hex(result.bytes)
        if (isImported(checksum)) {
            StagedFileMessage("Skipped ${result.fileName}: already imported", isError = false)
        } else {
            stage(checksum, result.lastModified ?: Clock.System.now())
            StagedFileMessage("Imported ${result.fileName}", isError = false)
        }
    } catch (expected: Exception) {
        StagedFileMessage("${result.fileName}: ${expected.message}", isError = true)
    }

/** Stages a picked Excel workbook's first worksheet, keeping the workbook so another sheet can be chosen later. */
internal suspend fun ImportEngine.stageXlsxFile(
    result: BinaryFilePickerResult,
    parser: XlsxParser,
    checksum: String,
    lastModified: Instant,
) {
    val sheetName = parser.sheetNames(result.bytes).firstOrNull() ?: ""
    val parsed = parser.parse(result.bytes, sheetName)
    createXlsxImport(
        fileName = result.fileName,
        headers = parsed.headers,
        rows = parsed.rows,
        fileChecksum = checksum,
        fileLastModified = lastModified,
        xlsxBytes = result.bytes,
        xlsxWorksheetName = sheetName,
    )
}
