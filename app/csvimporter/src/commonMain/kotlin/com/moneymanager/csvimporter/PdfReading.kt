package com.moneymanager.csvimporter

import com.moneymanager.domain.model.csv.PdfImportSource
import com.moneymanager.domain.model.csv.PdfTextLine
import com.moneymanager.pdf.createPdfTextExtractor
import com.moneymanager.pdf.extractLines

/**
 * The PDF in [bytes] as it is staged: the file and its text lines. Throws `PdfUnsupportedPlatformException` where
 * there's no pdfium, and `PdfParseException` for a file pdfium can't open.
 */
fun readPdf(bytes: ByteArray): PdfImportSource =
    PdfImportSource(
        fileBytes = bytes,
        lines = createPdfTextExtractor().extractLines(bytes).map { PdfTextLine(it.page, it.number, it.text) },
    )
