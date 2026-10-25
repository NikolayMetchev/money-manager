package com.moneymanager.pdf

import co.touchlab.kermit.Logger
import com.moneymanager.files.absolutePath
import com.moneymanager.files.appDataDirectory
import com.moneymanager.files.writeAtomically
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

/**
 * pdfium's document and text calls (`fpdfview.h`, `fpdf_text.h`), bound per platform over its handle type [H].
 * [extract] walks a document with them, so every platform that binds them reads a PDF as the same glyphs.
 */
internal abstract class Pdfium<H : Any> {
    /** Runs [block] on [bytes] opened as a document, kept readable until it closes; throws [pdfiumOpenError]. */
    protected abstract fun <R> withDocument(
        bytes: ByteArray,
        block: (H) -> R,
    ): R

    protected abstract fun pageCount(document: H): Int

    protected abstract fun loadPage(
        document: H,
        index: Int,
    ): H?

    protected abstract fun closePage(page: H)

    protected abstract fun loadTextPage(page: H): H?

    protected abstract fun closeTextPage(textPage: H)

    protected abstract fun charCount(textPage: H): Int

    protected abstract fun isGenerated(
        textPage: H,
        index: Int,
    ): Boolean

    protected abstract fun unicode(
        textPage: H,
        index: Int,
    ): Int

    /** The char's loose box's left and right, and its origin's y, or null when pdfium has none. */
    protected abstract fun position(
        textPage: H,
        index: Int,
    ): CharPosition?

    protected abstract fun fontSize(
        textPage: H,
        index: Int,
    ): Double

    protected data class CharPosition(
        val left: Double,
        val right: Double,
        val baseline: Double,
    )

    fun extract(bytes: ByteArray): List<PdfPage> =
        withDocument(bytes) { document ->
            (0 until pageCount(document)).map { index ->
                val page = loadPage(document, index) ?: throw PdfParseException("Page ${index + 1} can't be read")
                try {
                    PdfPage(index + 1, glyphs(page))
                } finally {
                    closePage(page)
                }
            }
        }

    private fun glyphs(page: H): List<PdfGlyph> {
        val textPage = loadTextPage(page) ?: return emptyList()
        try {
            return (0 until charCount(textPage)).mapNotNull { glyph(textPage, it) }
        } finally {
            closeTextPage(textPage)
        }
    }

    private fun glyph(
        textPage: H,
        index: Int,
    ): PdfGlyph? {
        // pdfium adds spaces and line breaks of its own; the line builder works spacing out from positions instead.
        if (isGenerated(textPage, index)) return null
        val text = pdfiumGlyphText(unicode(textPage, index)) ?: return null
        val position = position(textPage, index) ?: return null
        return PdfGlyph(text, position.left, position.right, position.baseline, fontSize(textPage, index))
    }
}

/** A [PdfTextExtractor] over the [Pdfium] [load] binds on first use. pdfium isn't thread-safe: one call at a time. */
internal class PdfiumTextExtractor(
    private val load: () -> Pdfium<*>,
) : PdfTextExtractor {
    private val lock = Mutex()
    private var pdfium: Pdfium<*>? = null

    override suspend fun extract(bytes: ByteArray): List<PdfPage> = lock.withLock { (pdfium ?: load().also { pdfium = it }).extract(bytes) }
}

private const val MAX_CODE_POINT = 0x10FFFF
private const val SUPPLEMENTARY_START = 0x10000
private const val HIGH_SURROGATE_START = 0xD800
private const val LOW_SURROGATE_START = 0xDC00
private const val SURROGATE_BITS = 10
private const val LOW_SURROGATE_MASK = 0x3FF

// Kotlin's whitespace also counts these no-break spaces, which Java's Character.isWhitespace (what the JVM
// extractor first used) keeps as text.
private val NO_BREAK_SPACES = setOf(' ', ' ', ' ')

/**
 * A glyph's text from the code point pdfium reports, or null for whitespace and code points that aren't characters,
 * which the line builder leaves to the glyphs' positions.
 */
internal fun pdfiumGlyphText(codePoint: Int): String? {
    if (codePoint <= 0 || codePoint > MAX_CODE_POINT) return null
    if (codePoint < SUPPLEMENTARY_START) {
        val char = codePoint.toChar()
        return char.takeUnless { it.isWhitespace() && it !in NO_BREAK_SPACES }?.toString()
    }
    val offset = codePoint - SUPPLEMENTARY_START
    return charArrayOf(
        (HIGH_SURROGATE_START + (offset shr SURROGATE_BITS)).toChar(),
        (LOW_SURROGATE_START + (offset and LOW_SURROGATE_MASK)).toChar(),
    ).concatToString()
}

/**
 * Writes a pdfium shared library this build carries to a file, as a library has to be one to be loaded: once under
 * `~/.moneymanager/native/pdfium-<build>/`, beside the license [notices] a binary distribution of it carries, and
 * returns its absolute path. [version] is the library's VERSION file, which names the build.
 */
internal object PdfiumLibraryFile {
    private const val RADIX = 36
    private val logger = Logger.withTag("PdfiumLibraryFile")

    fun unpack(
        name: String,
        bytes: ByteArray,
        version: String?,
        notices: ByteArray? = null,
    ): String {
        val build = version?.let(::buildNumber) ?: bytes.contentHashCode().toUInt().toString(16)
        val file =
            try {
                Path(appDataDirectory(), "native", "pdfium-$build", name).also { write(it, bytes, notices) }
            } catch (e: IOException) {
                // A read-only home (a sandboxed test): a directory of this process's own, never a shared name
                // another user could have put a library at.
                logger.w(e) { "Can't unpack pdfium under the home directory; using a temporary directory" }
                Path(SystemTemporaryDirectory, "moneymanager-pdfium-${Random.nextLong().toULong().toString(RADIX)}")
                    .also { SystemFileSystem.createDirectories(it, mustCreate = true) }
                    .let { Path(it, name) }
                    .also { write(it, bytes, notices) }
            }
        return absolutePath(file).toString()
    }

    private fun write(
        file: Path,
        bytes: ByteArray,
        notices: ByteArray?,
    ) {
        if (SystemFileSystem.metadataOrNull(file)?.size != bytes.size.toLong()) writeAtomically(file, bytes)
        if (notices != null) {
            val noticesFile = Path(checkNotNull(file.parent), "LICENSES.txt")
            if (SystemFileSystem.metadataOrNull(noticesFile)?.size != notices.size.toLong()) {
                writeAtomically(noticesFile, notices)
            }
        }
    }

    // VERSION holds `MAJOR=…`, `MINOR=…`, `BUILD=…`, `PATCH=…` lines.
    private fun buildNumber(version: String): String? =
        version
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("BUILD=") }
            ?.removePrefix("BUILD=")
}
