package com.moneymanager.pdf

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * [PdfTextExtractor] over pdfium's text API (`fpdf_text.h`), called through Java's foreign function API: no JNI
 * glue, only the shared library [PdfiumLibrary] unpacks. pdfium isn't thread-safe, so every call holds one lock.
 */
internal object PdfiumTextExtractor : PdfTextExtractor {
    private val lock = Any()
    private val functions by lazy { PdfiumFunctions(PdfiumLibrary.load()) }

    override fun extract(bytes: ByteArray): List<PdfPage> =
        synchronized(lock) {
            Arena.ofConfined().use { arena ->
                functions.extract(arena, bytes)
            }
        }
}

private class PdfiumFunctions(
    library: SymbolLookup,
) {
    private val linker = Linker.nativeLinker()
    private val lookup = library

    private fun function(
        name: String,
        descriptor: FunctionDescriptor,
    ): MethodHandle = linker.downcallHandle(lookup.findOrThrow(name), descriptor)

    private val pointer = ValueLayout.ADDRESS
    private val int = ValueLayout.JAVA_INT

    private val loadMemDocument =
        function("FPDF_LoadMemDocument64", FunctionDescriptor.of(pointer, pointer, ValueLayout.JAVA_LONG, pointer))
    private val getLastError = function("FPDF_GetLastError", FunctionDescriptor.of(ValueLayout.JAVA_LONG))
    private val closeDocument = function("FPDF_CloseDocument", FunctionDescriptor.ofVoid(pointer))
    private val getPageCount = function("FPDF_GetPageCount", FunctionDescriptor.of(int, pointer))
    private val loadPage = function("FPDF_LoadPage", FunctionDescriptor.of(pointer, pointer, int))
    private val closePage = function("FPDF_ClosePage", FunctionDescriptor.ofVoid(pointer))
    private val loadTextPage = function("FPDFText_LoadPage", FunctionDescriptor.of(pointer, pointer))
    private val closeTextPage = function("FPDFText_ClosePage", FunctionDescriptor.ofVoid(pointer))
    private val countChars = function("FPDFText_CountChars", FunctionDescriptor.of(int, pointer))
    private val getUnicode = function("FPDFText_GetUnicode", FunctionDescriptor.of(int, pointer, int))
    private val isGenerated = function("FPDFText_IsGenerated", FunctionDescriptor.of(int, pointer, int))
    private val getFontSize = function("FPDFText_GetFontSize", FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, pointer, int))

    // FS_RECTF* out: four floats, left, top, right, bottom.
    private val getLooseCharBox = function("FPDFText_GetLooseCharBox", FunctionDescriptor.of(int, pointer, int, pointer))

    // double* x, double* y out.
    private val getCharOrigin = function("FPDFText_GetCharOrigin", FunctionDescriptor.of(int, pointer, int, pointer, pointer))

    init {
        function("FPDF_InitLibrary", FunctionDescriptor.ofVoid()).invokeWithArguments()
    }

    fun extract(
        arena: Arena,
        bytes: ByteArray,
    ): List<PdfPage> {
        // pdfium reads the buffer for as long as the document is open, so it lives in the arena until then.
        val data = arena.allocate(bytes.size.toLong().coerceAtLeast(1))
        MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.size)
        val document = loadMemDocument.invokeWithArguments(data, bytes.size.toLong(), MemorySegment.NULL) as MemorySegment
        if (document == MemorySegment.NULL) {
            val error = getLastError.invokeWithArguments() as Long
            throw PdfParseException("Not a readable PDF (${ERRORS[error] ?: "pdfium error $error"})")
        }
        try {
            val pageCount = getPageCount.invokeWithArguments(document) as Int
            return (0 until pageCount).map { index -> PdfPage(index + 1, pageGlyphs(arena, document, index)) }
        } finally {
            closeDocument.invokeWithArguments(document)
        }
    }

    private fun pageGlyphs(
        arena: Arena,
        document: MemorySegment,
        index: Int,
    ): List<PdfGlyph> {
        val page = loadPage.invokeWithArguments(document, index) as MemorySegment
        if (page == MemorySegment.NULL) throw PdfParseException("Page ${index + 1} can't be read")
        try {
            val textPage = loadTextPage.invokeWithArguments(page) as MemorySegment
            if (textPage == MemorySegment.NULL) return emptyList()
            try {
                val box = arena.allocate(ValueLayout.JAVA_FLOAT, 4)
                val x = arena.allocate(ValueLayout.JAVA_DOUBLE)
                val y = arena.allocate(ValueLayout.JAVA_DOUBLE)
                val count = countChars.invokeWithArguments(textPage) as Int
                return (0 until count).mapNotNull { char ->
                    glyph(textPage, char, box, x, y)
                }
            } finally {
                closeTextPage.invokeWithArguments(textPage)
            }
        } finally {
            closePage.invokeWithArguments(page)
        }
    }

    private fun glyph(
        textPage: MemorySegment,
        index: Int,
        box: MemorySegment,
        x: MemorySegment,
        y: MemorySegment,
    ): PdfGlyph? {
        // pdfium adds spaces and line breaks of its own; the line builder works spacing out from positions instead.
        if (isGenerated.invokeWithArguments(textPage, index) as Int == 1) return null
        val codePoint = getUnicode.invokeWithArguments(textPage, index) as Int
        if (codePoint == 0 || Character.isWhitespace(codePoint) || !Character.isValidCodePoint(codePoint)) return null
        if (getLooseCharBox.invokeWithArguments(textPage, index, box) as Int == 0) return null
        if (getCharOrigin.invokeWithArguments(textPage, index, x, y) as Int == 0) return null
        return PdfGlyph(
            text = String(Character.toChars(codePoint)),
            left = box.getAtIndex(ValueLayout.JAVA_FLOAT, 0).toDouble(),
            right = box.getAtIndex(ValueLayout.JAVA_FLOAT, 2).toDouble(),
            baseline = y.get(ValueLayout.JAVA_DOUBLE, 0),
            size = getFontSize.invokeWithArguments(textPage, index) as Double,
        )
    }

    private companion object {
        // fpdfview.h's FPDF_ERR_* codes.
        val ERRORS =
            mapOf(
                1L to "unknown error",
                2L to "file not found or could not be opened",
                3L to "not a PDF or corrupted",
                4L to "password protected",
                5L to "unsupported security scheme",
                6L to "page not found or content error",
            )
    }
}
