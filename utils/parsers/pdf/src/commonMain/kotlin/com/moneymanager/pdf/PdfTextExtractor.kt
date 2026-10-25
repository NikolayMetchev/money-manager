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
 * the JVM; the Android, native and browser `actual`s return a parser that throws [PdfUnsupportedPlatformException]
 * (see [createPdfTextExtractor]).
 */
interface PdfTextExtractor {
    /**
     * Every page's glyphs.
     *
     * @throws PdfParseException if [bytes] isn't a PDF pdfium can open (corrupt, or password protected).
     */
    fun extract(bytes: ByteArray): List<PdfPage>
}

/** [bytes][PdfTextExtractor.extract] couldn't be read as a PDF. */
class PdfParseException(
    message: String,
) : IllegalArgumentException(message)

/** Thrown by the [PdfTextExtractor] of a platform without pdfium. */
class PdfUnsupportedPlatformException(
    message: String,
) : UnsupportedOperationException(message)

/**
 * The [PdfTextExtractor] of every platform without pdfium. Each call fails with [PdfUnsupportedPlatformException],
 * so a PDF fails on its own instead of aborting a whole import.
 */
internal object UnsupportedPdfTextExtractor : PdfTextExtractor {
    override fun extract(bytes: ByteArray): List<PdfPage> =
        throw PdfUnsupportedPlatformException(
            "PDF parsing is not supported on this platform: import PDFs with the desktop app or mm-jvm",
        )
}

/** This platform's [PdfTextExtractor]. */
@Suppress("ktlint:standard:function-naming")
expect fun createPdfTextExtractor(): PdfTextExtractor

/** The PDF's text as lines (see [PdfLineBuilder]). */
fun PdfTextExtractor.extractLines(bytes: ByteArray): List<PdfLine> = PdfLineBuilder.build(extract(bytes))
