package com.moneymanager.pdf

import kotlin.test.Test
import kotlin.test.assertEquals

class PdfiumOpenErrorTest {
    @Test
    fun eachPdfiumErrorCodeSaysWhyTheFileIsUnreadable() {
        val reasons = (1L..7L).map { pdfiumOpenError(it).message }

        assertEquals(
            listOf(
                "Not a readable PDF (unknown error)",
                "Not a readable PDF (file not found or could not be opened)",
                "Not a readable PDF (not a PDF or corrupted)",
                "Not a readable PDF (password protected)",
                "Not a readable PDF (unsupported security scheme)",
                "Not a readable PDF (page not found or content error)",
                "Not a readable PDF (pdfium error 7)",
            ),
            reasons,
        )
    }
}
