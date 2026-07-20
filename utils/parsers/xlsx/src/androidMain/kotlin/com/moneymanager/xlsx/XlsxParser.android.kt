package com.moneymanager.xlsx

import com.moneymanager.csv.CsvParseResult

/** Excel parsing (Apache POI) is JVM-only; Android reports the platform as unsupported. */
private class UnsupportedXlsxParser : XlsxParser {
    override fun sheetNames(bytes: ByteArray): List<String> = throw unsupported()

    override fun parse(
        bytes: ByteArray,
        sheetName: String?,
    ): CsvParseResult = throw unsupported()

    private fun unsupported() = XlsxUnsupportedPlatformException("Excel import is desktop-only")
}

@Suppress("ktlint:standard:function-naming")
actual fun createXlsxParser(): XlsxParser = UnsupportedXlsxParser()
