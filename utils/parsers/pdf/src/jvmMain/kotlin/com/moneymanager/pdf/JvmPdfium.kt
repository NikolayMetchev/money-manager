package com.moneymanager.pdf

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * [Pdfium] called through Java's foreign function API: no JNI glue, only the shared library [PdfiumLibrary]
 * unpacks.
 */
internal class JvmPdfium(
    library: SymbolLookup,
) : Pdfium<MemorySegment>() {
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

    // The out-parameters, reused: calls take turns (PdfiumTextExtractor).
    private val box = Arena.global().allocate(ValueLayout.JAVA_FLOAT, 4)
    private val x = Arena.global().allocate(ValueLayout.JAVA_DOUBLE)
    private val y = Arena.global().allocate(ValueLayout.JAVA_DOUBLE)

    init {
        function("FPDF_InitLibrary", FunctionDescriptor.ofVoid()).invokeWithArguments()
    }

    override fun <R> withDocument(
        bytes: ByteArray,
        block: (MemorySegment) -> R,
    ): R =
        Arena.ofConfined().use { arena ->
            // pdfium reads the buffer for as long as the document is open, so it lives in the arena until then.
            val data = arena.allocate(bytes.size.toLong().coerceAtLeast(1))
            MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.size)
            val document = loadMemDocument.invokeWithArguments(data, bytes.size.toLong(), MemorySegment.NULL) as MemorySegment
            if (document == MemorySegment.NULL) throw pdfiumOpenError(getLastError.invokeWithArguments() as Long)
            try {
                block(document)
            } finally {
                closeDocument.invokeWithArguments(document)
            }
        }

    override fun pageCount(document: MemorySegment) = getPageCount.invokeWithArguments(document) as Int

    override fun loadPage(
        document: MemorySegment,
        index: Int,
    ) = (loadPage.invokeWithArguments(document, index) as MemorySegment).orNull()

    override fun closePage(page: MemorySegment) {
        closePage.invokeWithArguments(page)
    }

    override fun loadTextPage(page: MemorySegment) = (loadTextPage.invokeWithArguments(page) as MemorySegment).orNull()

    override fun closeTextPage(textPage: MemorySegment) {
        closeTextPage.invokeWithArguments(textPage)
    }

    override fun charCount(textPage: MemorySegment) = countChars.invokeWithArguments(textPage) as Int

    override fun isGenerated(
        textPage: MemorySegment,
        index: Int,
    ) = isGenerated.invokeWithArguments(textPage, index) as Int == 1

    override fun unicode(
        textPage: MemorySegment,
        index: Int,
    ) = getUnicode.invokeWithArguments(textPage, index) as Int

    override fun position(
        textPage: MemorySegment,
        index: Int,
    ): CharPosition? {
        if (getLooseCharBox.invokeWithArguments(textPage, index, box) as Int == 0) return null
        if (getCharOrigin.invokeWithArguments(textPage, index, x, y) as Int == 0) return null
        return CharPosition(
            left = box.getAtIndex(ValueLayout.JAVA_FLOAT, 0).toDouble(),
            right = box.getAtIndex(ValueLayout.JAVA_FLOAT, 2).toDouble(),
            baseline = y.get(ValueLayout.JAVA_DOUBLE, 0),
        )
    }

    override fun fontSize(
        textPage: MemorySegment,
        index: Int,
    ) = getFontSize.invokeWithArguments(textPage, index) as Double

    private fun MemorySegment.orNull() = takeUnless { it == MemorySegment.NULL }
}
