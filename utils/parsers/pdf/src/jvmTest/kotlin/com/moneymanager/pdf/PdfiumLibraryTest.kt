package com.moneymanager.pdf

import kotlin.test.Test
import kotlin.test.assertFailsWith

class PdfiumLibraryTest {
    @Test
    fun `a build without this platform library says so`() {
        assertFailsWith<PdfUnsupportedPlatformException> { PdfiumLibrary.load("libpdfium.missing") }
    }
}
