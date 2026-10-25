package com.moneymanager.pdf

private val extractor = PdfiumTextExtractor { NativePdfium.load() }

@Suppress("ktlint:standard:function-naming")
actual fun createPdfTextExtractor(): PdfTextExtractor = extractor
