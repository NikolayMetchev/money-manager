package com.moneymanager.pdf

import com.moneymanager.pdf.testing.STATEMENT_EXCERPT_PDF
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PdfiumTextExtractorTest {
    private val extractor = createPdfTextExtractor()

    // A platform without pdfium yet only has to say so.
    private val unsupported = extractor == UnsupportedPdfTextExtractor

    @Test
    fun `reads each line of text where the page draws it`() =
        runTest {
            if (unsupported) {
                assertFailsWith<PdfUnsupportedPlatformException> { extractor.extract(STATEMENT_EXCERPT_PDF) }
                return@runTest
            }
            val lines = extractor.extractLines(STATEMENT_EXCERPT_PDF)
            assertEquals(
                listOf("Statement Date 19/01/13", "CR"),
                listOf(lines[0].text, lines[2].text),
            )
            val row = lines[1].text
            assertTrue(Regex("""^Jan 2\s+PAYMENT RECEIVED - THANK YOU\s{2,}2,609\.36$""").matches(row), row)
            assertEquals(listOf(1, 1, 1), lines.map { it.page })
            assertEquals(listOf(1, 2, 3), lines.map { it.number })
        }

    @Test
    fun `bytes that aren't a PDF fail as a parse error`() =
        runTest {
            if (unsupported) return@runTest
            assertFailsWith<PdfParseException> {
                extractor.extract("not a pdf".encodeToByteArray())
            }
        }
}
