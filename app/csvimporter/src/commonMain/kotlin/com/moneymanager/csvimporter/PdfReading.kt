package com.moneymanager.csvimporter

import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.csv.PdfImportSource
import com.moneymanager.domain.model.csv.PdfTextLine
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createPdfImport
import com.moneymanager.pdf.createPdfTextExtractor
import com.moneymanager.pdf.extractLines
import kotlin.time.Instant

/**
 * The PDF in [bytes] as it is staged: the file and its text lines. Throws `PdfUnsupportedPlatformException` where
 * there's no pdfium, and `PdfParseException` for a file pdfium can't open.
 */
suspend fun readPdf(bytes: ByteArray): PdfImportSource =
    PdfImportSource(
        fileBytes = bytes,
        lines = createPdfTextExtractor().extractLines(bytes).map { PdfTextLine(it.page, it.number, it.text) },
    )

/**
 * Stages the PDF in [bytes] as a CSV import: its text lines, one raw row each, until a strategy's line rules
 * assemble them ([PdfRowAssembler]). The one way a PDF is staged, from an import folder or a picked file. Throws
 * as [readPdf] does: `PdfUnsupportedPlatformException` where there's no pdfium, which the scanner records as a
 * per-file failure and the Import PDF button shows.
 */
suspend fun ImportEngine.stagePdfImport(
    fileName: String,
    bytes: ByteArray,
    fileChecksum: String,
    fileLastModified: Instant,
): CsvImportId {
    val pdf = readPdf(bytes)
    val staged = PdfRowAssembler.rawLines(pdf.lines)
    return createPdfImport(
        fileName = fileName,
        headers = staged.headers,
        rows = staged.rows,
        fileChecksum = fileChecksum,
        fileLastModified = fileLastModified,
        pdf = pdf,
    )
}
