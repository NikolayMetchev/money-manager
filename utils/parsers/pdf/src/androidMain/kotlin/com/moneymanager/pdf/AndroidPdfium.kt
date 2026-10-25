package com.moneymanager.pdf

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer

/**
 * [Pdfium] on Android, which has no FFM: JNA calls the `libpdfium.so` the APK carries (`:pdfium_library`, for
 * arm64-v8a and x86_64). An ABI without one reports the platform unsupported.
 */
internal class AndroidPdfium private constructor(
    private val api: Api,
) : Pdfium<Pointer>() {
    // pdfium's own names, which JNA looks the symbols up by.
    @Suppress("FunctionNaming", "ktlint:standard:function-naming")
    internal interface Api : Library {
        fun FPDF_InitLibrary()

        fun FPDF_LoadMemDocument64(
            data: Pointer,
            size: Long,
            password: String?,
        ): Pointer?

        fun FPDF_GetLastError(): NativeLong

        fun FPDF_CloseDocument(document: Pointer)

        fun FPDF_GetPageCount(document: Pointer): Int

        fun FPDF_LoadPage(
            document: Pointer,
            index: Int,
        ): Pointer?

        fun FPDF_ClosePage(page: Pointer)

        fun FPDFText_LoadPage(page: Pointer): Pointer?

        fun FPDFText_ClosePage(textPage: Pointer)

        fun FPDFText_CountChars(textPage: Pointer): Int

        fun FPDFText_GetUnicode(
            textPage: Pointer,
            index: Int,
        ): Int

        fun FPDFText_IsGenerated(
            textPage: Pointer,
            index: Int,
        ): Int

        fun FPDFText_GetFontSize(
            textPage: Pointer,
            index: Int,
        ): Double

        // FS_RECTF* out: four floats, left, top, right, bottom.
        fun FPDFText_GetLooseCharBox(
            textPage: Pointer,
            index: Int,
            box: FloatArray,
        ): Int

        fun FPDFText_GetCharOrigin(
            textPage: Pointer,
            index: Int,
            x: DoubleArray,
            y: DoubleArray,
        ): Int
    }

    // The out-parameters, reused: calls take turns (PdfiumTextExtractor).
    private val box = FloatArray(BOX_FIELDS)
    private val x = DoubleArray(1)
    private val y = DoubleArray(1)

    override fun <R> withDocument(
        bytes: ByteArray,
        block: (Pointer) -> R,
    ): R {
        // pdfium reads the buffer for as long as the document is open, so it stays referenced until then.
        val data = Memory(bytes.size.toLong().coerceAtLeast(1)).apply { write(0, bytes, 0, bytes.size) }
        try {
            val document =
                api.FPDF_LoadMemDocument64(data, bytes.size.toLong(), null)
                    ?: throw pdfiumOpenError(api.FPDF_GetLastError().toLong())
            try {
                return block(document)
            } finally {
                api.FPDF_CloseDocument(document)
            }
        } finally {
            data.close()
        }
    }

    override fun pageCount(document: Pointer) = api.FPDF_GetPageCount(document)

    override fun loadPage(
        document: Pointer,
        index: Int,
    ) = api.FPDF_LoadPage(document, index)

    override fun closePage(page: Pointer) = api.FPDF_ClosePage(page)

    override fun loadTextPage(page: Pointer) = api.FPDFText_LoadPage(page)

    override fun closeTextPage(textPage: Pointer) = api.FPDFText_ClosePage(textPage)

    override fun charCount(textPage: Pointer) = api.FPDFText_CountChars(textPage)

    override fun isGenerated(
        textPage: Pointer,
        index: Int,
    ) = api.FPDFText_IsGenerated(textPage, index) == 1

    override fun unicode(
        textPage: Pointer,
        index: Int,
    ) = api.FPDFText_GetUnicode(textPage, index)

    override fun position(
        textPage: Pointer,
        index: Int,
    ): CharPosition? {
        if (api.FPDFText_GetLooseCharBox(textPage, index, box) == 0) return null
        if (api.FPDFText_GetCharOrigin(textPage, index, x, y) == 0) return null
        return CharPosition(left = box[0].toDouble(), right = box[2].toDouble(), baseline = y[0])
    }

    override fun fontSize(
        textPage: Pointer,
        index: Int,
    ) = api.FPDFText_GetFontSize(textPage, index)

    companion object {
        private const val BOX_FIELDS = 4

        fun load(): AndroidPdfium {
            val api =
                try {
                    Native.load("pdfium", Api::class.java)
                } catch (e: LinkageError) {
                    // No libpdfium.so (or libjnidispatch.so) for this ABI.
                    throw PdfUnsupportedPlatformException("This device has no pdfium library: ${e.message}")
                }
            api.FPDF_InitLibrary()
            return AndroidPdfium(api)
        }
    }
}
