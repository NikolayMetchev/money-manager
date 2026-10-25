package com.moneymanager.pdf

/**
 * One character a PDF page draws, where it draws it: [left]/[right] in points from the page's left edge, [baseline]
 * in points from its bottom (PDF's y axis points up), [size] the font size.
 */
data class PdfGlyph(
    val text: String,
    val left: Double,
    val right: Double,
    val baseline: Double,
    val size: Double,
)

/** A page's glyphs, in no particular order; [number] is 1-based. */
data class PdfPage(
    val number: Int,
    val glyphs: List<PdfGlyph>,
)

/**
 * Reads the characters a PDF draws, with their positions. A PDF stores no reading order (its text operators can
 * draw a page in any order), so lines are rebuilt from the positions by [PdfLineBuilder]. Implemented with pdfium on
 * every platform (in the browser its WebAssembly build, which loads asynchronously, hence `suspend`).
 */
interface PdfTextExtractor {
    /**
     * Every page's glyphs.
     *
     * @throws PdfParseException if [bytes] isn't a PDF pdfium can open (corrupt, or password protected).
     * @throws PdfUnsupportedPlatformException if this build has no pdfium for the OS, CPU or ABI it runs on.
     */
    suspend fun extract(bytes: ByteArray): List<PdfPage>
}

/** [bytes][PdfTextExtractor.extract] couldn't be read as a PDF. */
class PdfParseException(
    message: String,
) : IllegalArgumentException(message)

/**
 * pdfium can't load here: this build carries none for the OS, CPU or ABI it runs on. Each PDF fails on its own, so
 * one never aborts a whole import.
 */
class PdfUnsupportedPlatformException(
    message: String,
) : UnsupportedOperationException(message)

/** This platform's [PdfTextExtractor]. */
@Suppress("ktlint:standard:function-naming")
expect fun createPdfTextExtractor(): PdfTextExtractor

/** The PDF's text as lines (see [PdfLineBuilder]). */
suspend fun PdfTextExtractor.extractLines(bytes: ByteArray): List<PdfLine> = PdfLineBuilder.build(extract(bytes))

/** Why pdfium couldn't open a document, from `FPDF_GetLastError`'s code (fpdfview.h's `FPDF_ERR_*`). */
internal fun pdfiumOpenError(code: Long): PdfParseException {
    val reason =
        when (code) {
            1L -> "unknown error"
            2L -> "file not found or could not be opened"
            3L -> "not a PDF or corrupted"
            4L -> "password protected"
            5L -> "unsupported security scheme"
            6L -> "page not found or content error"
            else -> "pdfium error $code"
        }
    return PdfParseException("Not a readable PDF ($reason)")
}
