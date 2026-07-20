package com.moneymanager.xlsx

import com.moneymanager.csv.CsvParseResult
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.ByteArrayInputStream
import java.util.Locale

/** Apache POI-backed [XlsxParser]. Reads `.xlsx` (OOXML) and legacy `.xls` (HSSF) workbooks. */
private class PoiXlsxParser : XlsxParser {
    override fun sheetNames(bytes: ByteArray): List<String> =
        WorkbookFactory.create(ByteArrayInputStream(bytes)).use { workbook ->
            (0 until workbook.numberOfSheets).map { workbook.getSheetName(it) }
        }

    override fun parse(
        bytes: ByteArray,
        sheetName: String?,
    ): CsvParseResult =
        WorkbookFactory.create(ByteArrayInputStream(bytes)).use { workbook ->
            val sheet: Sheet =
                if (sheetName == null) {
                    workbook.getSheetAt(0)
                } else {
                    workbook.getSheet(sheetName)
                        ?: throw IllegalArgumentException("No worksheet named \"$sheetName\" in this workbook")
                }
            val formatter = DataFormatter(Locale.ROOT)
            val rowIterator = sheet.rowIterator()
            if (!rowIterator.hasNext()) {
                return CsvParseResult(headers = emptyList(), rows = emptyList())
            }

            val headerRow = rowIterator.next()
            val columnCount = headerRow.lastCellNum.coerceAtLeast(0).toInt()
            val headers = rowValues(headerRow, columnCount, formatter)

            val rows = mutableListOf<List<String>>()
            while (rowIterator.hasNext()) {
                rows.add(rowValues(rowIterator.next(), columnCount, formatter))
            }
            CsvParseResult(headers = headers, rows = rows)
        }

    private fun rowValues(
        row: Row,
        columnCount: Int,
        formatter: DataFormatter,
    ): List<String> =
        (0 until columnCount).map { columnIndex ->
            row.getCell(columnIndex)?.let { formatter.formatCellValue(it) }.orEmpty()
        }
}

@Suppress("ktlint:standard:function-naming")
actual fun createXlsxParser(): XlsxParser = PoiXlsxParser()
