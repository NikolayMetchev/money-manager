package com.moneymanager.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PdfiumTextExtractorTest {
    @Test
    fun `reads each line of text where the page draws it`() {
        val pdf =
            minimalPdf(
                // Drawn bottom line first: the extractor must order by position, not by drawing order.
                Text(72, 680, "Jan 2   PAYMENT RECEIVED - THANK YOU"),
                Text(400, 680, "2,609.36"),
                Text(72, 700, "Statement Date 19/01/13"),
                Text(72, 660, "CR"),
            )
        val lines = createPdfTextExtractor().extractLines(pdf)
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

    private class Text(
        val x: Int,
        val y: Int,
        val text: String,
    )

    /** A one-page PDF drawing [texts] in 12pt Helvetica, its cross-reference offsets computed as it's written. */
    private fun minimalPdf(vararg texts: Text): ByteArray {
        val content =
            texts.joinToString("\n") { "BT /F1 12 Tf ${it.x} ${it.y} Td (${it.text}) Tj ET" }
        val objects =
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> " +
                    "/Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
                "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            )
        val pdf = StringBuilder("%PDF-1.4\n")
        val offsets =
            objects.mapIndexed { i, body ->
                pdf.length.also { pdf.append("${i + 1} 0 obj\n$body\nendobj\n") }
            }
        val xref = pdf.length
        pdf.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { pdf.append("%010d 00000 n \n".format(it)) }
        pdf.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return pdf.toString().encodeToByteArray()
    }
}
