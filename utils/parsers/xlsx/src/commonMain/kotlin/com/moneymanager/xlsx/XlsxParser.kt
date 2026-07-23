package com.moneymanager.xlsx

import com.moneymanager.csv.CsvParseResult

/**
 * Reads `.xlsx` (and legacy `.xls`) workbooks as tabular data, so a worksheet can be treated exactly
 * like a CSV file: first row = headers, remaining rows = data. Implemented with Apache POI on JVM only;
 * the Android `actual` reports the platform as unsupported (see [createXlsxParser]).
 */
interface XlsxParser {
    /** Worksheet names in workbook order. */
    fun sheetNames(bytes: ByteArray): List<String>

    /**
     * Parses one worksheet into headers + rows. [sheetName] null selects the first sheet.
     *
     * @throws IllegalArgumentException if [sheetName] doesn't name a sheet in the workbook.
     */
    fun parse(
        bytes: ByteArray,
        sheetName: String? = null,
    ): CsvParseResult
}

/** Thrown by the Android [XlsxParser] actual: Excel parsing is desktop-only (Apache POI is JVM-only). */
class XlsxUnsupportedPlatformException(
    message: String,
) : UnsupportedOperationException(message)

@Suppress("ktlint:standard:function-naming")
expect fun createXlsxParser(): XlsxParser
