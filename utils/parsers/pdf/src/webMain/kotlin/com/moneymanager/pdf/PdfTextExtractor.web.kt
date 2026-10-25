@file:OptIn(ExperimentalWasmJsInterop::class)

package com.moneymanager.pdf

import kotlinx.coroutines.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toInt8Array
import kotlin.js.ExperimentalWasmJsInterop

@Suppress("ktlint:standard:function-naming")
actual fun createPdfTextExtractor(): PdfTextExtractor = WebPdfiumTextExtractor

/**
 * [PdfTextExtractor] over pdfium's WebAssembly build (`@embedpdf/pdfium`): the same text calls [Pdfium] makes on
 * the other platforms, so a PDF reads as the same lines in the browser. They run in one JavaScript loop per document
 * rather than one interop call per glyph. The module loads on first use. One call runs at a time, as pdfium isn't
 * reentrant.
 */
internal object WebPdfiumTextExtractor : PdfTextExtractor {
    private val lock = Mutex()
    private var pdfium: JsAny? = null

    override suspend fun extract(bytes: ByteArray): List<PdfPage> =
        lock.withLock {
            val module = pdfium ?: init(newObject()).await<JsAny>().also(::initLibrary).also { pdfium = it }
            val result = extractPages(module, bytes.toInt8Array())
            val error = openError(result)
            if (error >= 0) throw pdfiumOpenError(error.toLong())
            (0 until pageCount(result)).map { index ->
                if (pageMissing(result, index)) throw PdfParseException("Page ${index + 1} can't be read")
                PdfPage(index + 1, glyphs(pageNumbers(result, index)))
            }
        }
}

// A page's glyphs as extractPages lays them out: code point, left, right, baseline and font size for each.
private const val GLYPH_FIELDS = 5

private fun glyphs(numbers: JsAny): List<PdfGlyph> =
    (0 until length(numbers) / GLYPH_FIELDS).mapNotNull { glyph ->
        val at = glyph * GLYPH_FIELDS
        pdfiumGlyphText(numberAt(numbers, at).toInt())?.let { text ->
            PdfGlyph(
                text = text,
                left = numberAt(numbers, at + 1),
                right = numberAt(numbers, at + 2),
                baseline = numberAt(numbers, at + 3),
                size = numberAt(numbers, at + 4),
            )
        }
    }

private fun newObject(): JsAny = js("new Object()")

// The parameter is read by the JavaScript.
@Suppress("UnusedParameter")
private fun initLibrary(module: JsAny): Unit = js("module.FPDF_InitLibrary()")

/**
 * Every page's glyphs in one call, so the loop stays in JavaScript: `{error: code}` when pdfium can't open the
 * document, else `{pages}`, each page a Float64Array of [GLYPH_FIELDS] numbers per glyph, or null when it can't be
 * loaded. pdfium's own spaces and line breaks (`FPDFText_IsGenerated`) are skipped, as on the JVM.
 */
@Suppress("UnusedParameter", "LongMethod")
private fun extractPages(
    module: JsAny,
    bytes: Int8Array,
): JsAny =
    js(
        """(function () {
            var p = module.pdfium;
            var heap = p.wasmExports;
            var data = heap.malloc(bytes.length || 1);
            p.HEAPU8.set(new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.length), data);
            var doc = module.FPDF_LoadMemDocument(data, bytes.length, null);
            if (!doc) {
                var code = module.FPDF_GetLastError();
                heap.free(data);
                return { error: code };
            }
            var box = heap.malloc(16);
            var origin = heap.malloc(16);
            var pages = [];
            try {
                var count = module.FPDF_GetPageCount(doc);
                for (var i = 0; i < count; i++) {
                    var page = module.FPDF_LoadPage(doc, i);
                    if (!page) {
                        pages.push(null);
                        continue;
                    }
                    var numbers = [];
                    var text = module.FPDFText_LoadPage(page);
                    if (text) {
                        var chars = module.FPDFText_CountChars(text);
                        for (var c = 0; c < chars; c++) {
                            if (module.FPDFText_IsGenerated(text, c) === 1) continue;
                            if (!module.FPDFText_GetLooseCharBox(text, c, box)) continue;
                            if (!module.FPDFText_GetCharOrigin(text, c, origin, origin + 8)) continue;
                            numbers.push(
                                module.FPDFText_GetUnicode(text, c),
                                p.HEAPF32[box >> 2],
                                p.HEAPF32[(box >> 2) + 2],
                                p.HEAPF64[(origin + 8) >> 3],
                                module.FPDFText_GetFontSize(text, c)
                            );
                        }
                        module.FPDFText_ClosePage(text);
                    }
                    module.FPDF_ClosePage(page);
                    pages.push(new Float64Array(numbers));
                }
            } finally {
                module.FPDF_CloseDocument(doc);
                heap.free(origin);
                heap.free(box);
                heap.free(data);
            }
            return { error: -1, pages: pages };
        })()""",
    )

@Suppress("UnusedParameter")
private fun openError(result: JsAny): Int = js("result.error")

@Suppress("UnusedParameter")
private fun pageCount(result: JsAny): Int = js("result.pages.length")

@Suppress("UnusedParameter")
private fun pageMissing(
    result: JsAny,
    index: Int,
): Boolean = js("result.pages[index] === null")

@Suppress("UnusedParameter")
private fun pageNumbers(
    result: JsAny,
    index: Int,
): JsAny = js("result.pages[index]")

@Suppress("UnusedParameter")
private fun length(numbers: JsAny): Int = js("numbers.length")

@Suppress("UnusedParameter")
private fun numberAt(
    numbers: JsAny,
    index: Int,
): Double = js("numbers[index]")
