package com.moneymanager.xlsx

import com.moneymanager.csv.CsvParseResult
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.ByteArrayInputStream
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val TIME_OF_DAY = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
private val QUOTED_FORMAT_TEXT = Regex("\"[^\"]*\"")

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
            row.getCell(columnIndex)?.let { cellText(it, formatter) }.orEmpty()
        }

    /**
     * The cell as its format shows it, plus the time of day a date format would hide: a spreadsheet can hold
     * `43940.25` under `D/M/YYYY`, and a time thrown away here can't be recovered by any strategy. A cell holding
     * midnight has no time to keep, so a date-only column still reads as dates.
     */
    private fun cellText(
        cell: Cell,
        formatter: DataFormatter,
    ): String {
        val shown = formatter.formatCellValue(cell)
        if (cell.cellType != CellType.NUMERIC || !DateUtil.isCellDateFormatted(cell)) return shown
        val visibleFormat =
            cell.cellStyle.dataFormatString
                .replace(QUOTED_FORMAT_TEXT, "")
                .lowercase(Locale.ROOT)
        if ('h' in visibleFormat || 's' in visibleFormat) return shown
        val time = cell.localDateTimeCellValue.toLocalTime().truncatedTo(ChronoUnit.SECONDS)
        return if (time.toSecondOfDay() == 0) shown else "$shown ${TIME_OF_DAY.format(time)}"
    }
}

@Suppress("ktlint:standard:function-naming")
actual fun createXlsxParser(): XlsxParser = PoiXlsxParser()
