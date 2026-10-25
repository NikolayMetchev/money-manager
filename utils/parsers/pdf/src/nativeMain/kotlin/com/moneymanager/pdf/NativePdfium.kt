@file:OptIn(ExperimentalForeignApi::class)

package com.moneymanager.pdf

import com.moneymanager.pdf.pdfium.mm_pdfium_char_count
import com.moneymanager.pdf.pdfium.mm_pdfium_close_document
import com.moneymanager.pdf.pdfium.mm_pdfium_close_page
import com.moneymanager.pdf.pdfium.mm_pdfium_close_text_page
import com.moneymanager.pdf.pdfium.mm_pdfium_file_name
import com.moneymanager.pdf.pdfium.mm_pdfium_font_size
import com.moneymanager.pdf.pdfium.mm_pdfium_is_generated
import com.moneymanager.pdf.pdfium.mm_pdfium_last_error
import com.moneymanager.pdf.pdfium.mm_pdfium_library
import com.moneymanager.pdf.pdfium.mm_pdfium_licenses
import com.moneymanager.pdf.pdfium.mm_pdfium_load
import com.moneymanager.pdf.pdfium.mm_pdfium_load_document
import com.moneymanager.pdf.pdfium.mm_pdfium_load_page
import com.moneymanager.pdf.pdfium.mm_pdfium_load_text_page
import com.moneymanager.pdf.pdfium.mm_pdfium_page_count
import com.moneymanager.pdf.pdfium.mm_pdfium_position
import com.moneymanager.pdf.pdfium.mm_pdfium_unicode
import com.moneymanager.pdf.pdfium.mm_pdfium_version
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

/**
 * [Pdfium] in a native binary: the loader (`pdfium_loader.c`) embeds the target's pdfium library, which
 * [PdfiumLibraryFile] writes to a file for the loader to open.
 */
internal object NativePdfium : Pdfium<COpaquePointer>() {
    private const val POSITION_FIELDS = 3

    // The out-parameter, reused: calls take turns (PdfiumTextExtractor).
    private val position = nativeHeap.allocArray<DoubleVar>(POSITION_FIELDS)

    fun load(): NativePdfium {
        val file =
            PdfiumLibraryFile.unpack(
                name = checkNotNull(mm_pdfium_file_name()).toKString(),
                bytes = embedded(::mm_pdfium_library),
                version = embedded(::mm_pdfium_version).decodeToString(),
                notices = embedded(::mm_pdfium_licenses),
            )
        mm_pdfium_load(file)?.let { throw PdfUnsupportedPlatformException("Can't load pdfium: ${it.toKString()}") }
        return this
    }

    private fun embedded(file: (CPointer<ULongVar>?) -> CPointer<UByteVar>?): ByteArray =
        memScoped {
            val size = alloc<ULongVar>()
            checkNotNull(file(size.ptr)).readBytes(size.value.toInt())
        }

    override fun <R> withDocument(
        bytes: ByteArray,
        block: (COpaquePointer) -> R,
    ): R =
        // pdfium reads the buffer for as long as the document is open, so it stays pinned until then.
        bytes.copyOf(bytes.size.coerceAtLeast(1)).usePinned { pinned ->
            val document =
                mm_pdfium_load_document(pinned.addressOf(0), bytes.size.toULong())
                    ?: throw pdfiumOpenError(mm_pdfium_last_error().toLong())
            try {
                block(document)
            } finally {
                mm_pdfium_close_document(document)
            }
        }

    override fun pageCount(document: COpaquePointer) = mm_pdfium_page_count(document)

    override fun loadPage(
        document: COpaquePointer,
        index: Int,
    ) = mm_pdfium_load_page(document, index)

    override fun closePage(page: COpaquePointer) = mm_pdfium_close_page(page)

    override fun loadTextPage(page: COpaquePointer) = mm_pdfium_load_text_page(page)

    override fun closeTextPage(textPage: COpaquePointer) = mm_pdfium_close_text_page(textPage)

    override fun charCount(textPage: COpaquePointer) = mm_pdfium_char_count(textPage)

    override fun isGenerated(
        textPage: COpaquePointer,
        index: Int,
    ) = mm_pdfium_is_generated(textPage, index) == 1

    override fun unicode(
        textPage: COpaquePointer,
        index: Int,
    ) = mm_pdfium_unicode(textPage, index).toInt()

    override fun position(
        textPage: COpaquePointer,
        index: Int,
    ): CharPosition? {
        if (mm_pdfium_position(textPage, index, position) == 0) return null
        return CharPosition(left = position[0], right = position[1], baseline = position[2])
    }

    override fun fontSize(
        textPage: COpaquePointer,
        index: Int,
    ) = mm_pdfium_font_size(textPage, index)
}
