package com.moneymanager.pdf

private val extractor = PdfiumTextExtractor { JvmPdfium(PdfiumLibrary.load()) }

@Suppress("ktlint:standard:function-naming")
actual fun createPdfTextExtractor(): PdfTextExtractor = extractor
