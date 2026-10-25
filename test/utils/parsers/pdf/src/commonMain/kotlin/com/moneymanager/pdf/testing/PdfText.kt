package com.moneymanager.pdf.testing

/** One string a [minimalPdf] page draws, its baseline starting at ([x], [y]) points from the bottom left. */
class PdfText(
    val x: Int,
    val y: Int,
    val text: String,
)

/** A one-page PDF drawing [texts] in 12pt Helvetica, its cross-reference offsets computed as it's written. */
fun minimalPdf(vararg texts: PdfText): ByteArray {
    val content = texts.joinToString("\n") { "BT /F1 12 Tf ${it.x} ${it.y} Td (${it.text}) Tj ET" }
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
    offsets.forEach { pdf.append("${it.toString().padStart(XREF_OFFSET_DIGITS, '0')} 00000 n \n") }
    pdf.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    return pdf.toString().encodeToByteArray()
}

/**
 * The statement excerpt every platform's extractor test reads: drawn bottom line first, so an extractor must order
 * lines by position, not by drawing order.
 */
val STATEMENT_EXCERPT_PDF: ByteArray by lazy {
    minimalPdf(
        PdfText(72, 680, "Jan 2   PAYMENT RECEIVED - THANK YOU"),
        PdfText(400, 680, "2,609.36"),
        PdfText(72, 700, "Statement Date 19/01/13"),
        PdfText(72, 660, "CR"),
    )
}

private const val XREF_OFFSET_DIGITS = 10
