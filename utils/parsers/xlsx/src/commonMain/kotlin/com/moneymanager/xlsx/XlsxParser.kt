package com.moneymanager.xlsx

import com.moneymanager.csv.CsvParseResult

/**
 * Reads `.xlsx` (and legacy `.xls`) workbooks as tabular data, so a worksheet can be treated exactly
 * like a CSV file: first row = headers, remaining rows = data. Implemented with Apache POI on JVM only;
 * the Android, native and browser `actual`s return [UnsupportedXlsxParser] (see [createXlsxParser]).
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

/** Thrown by [UnsupportedXlsxParser]: Excel parsing needs Apache POI, which is JVM-only. */
class XlsxUnsupportedPlatformException(
    message: String,
) : UnsupportedOperationException(message)

/**
 * The [XlsxParser] of every platform without Apache POI (Android, native, the browser). Each call fails with
 * [XlsxUnsupportedPlatformException], so an xlsx file fails on its own instead of aborting a whole import.
 */
internal object UnsupportedXlsxParser : XlsxParser {
    override fun sheetNames(bytes: ByteArray): List<String> = throw unsupported()

    override fun parse(
        bytes: ByteArray,
        sheetName: String?,
    ): CsvParseResult = throw unsupported()

    private fun unsupported() = XlsxUnsupportedPlatformException("XLSX import is not supported on this platform")
}

@Suppress("ktlint:standard:function-naming")
expect fun createXlsxParser(): XlsxParser
