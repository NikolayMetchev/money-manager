package com.moneymanager.pdf

import com.moneymanager.pdf.testing.STATEMENT_EXCERPT_PDF
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PdfiumTextExtractorTest {
    @Test
    fun `reads each line of text where the page draws it`() {
        val lines = createPdfTextExtractor().extractLines(STATEMENT_EXCERPT_PDF)
        assertEquals(
            listOf("Statement Date 19/01/13", "CR"),
            listOf(lines[0].text, lines[2].text),
        )
        val row = lines[1].text
        assert(Regex("""^Jan 2\s+PAYMENT RECEIVED - THANK YOU\s{2,}2,609\.36$""").matches(row)) { row }
        assertEquals(listOf(1, 1, 1), lines.map { it.page })
        assertEquals(listOf(1, 2, 3), lines.map { it.number })
    }

    @Test
    fun `bytes that aren't a PDF fail as a parse error`() {
        assertFailsWith<PdfParseException> {
            createPdfTextExtractor().extract("not a pdf".encodeToByteArray())
        }
    }
}
