package com.moneymanager.xlsx

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class XlsxParserTest {
    private val parser = createXlsxParser()

    @Test
    fun parse_singleSheetWorkbook_readsHeadersAndRows() {
        val bytes =
            workbookBytes {
                sheet("Sheet1") {
                    row("Transaction Date", "Description", "Amount Processed", "Currency ")
                    row("11/26/2021", "Card Load", "200", "GBP")
                    row("11/27/2021", "Coffee Shop", "-4.5", "GBP")
                }
            }

        val result = parser.parse(bytes)

        assertEquals(listOf("Transaction Date", "Description", "Amount Processed", "Currency "), result.headers)
        assertEquals(2, result.rows.size)
        assertEquals(listOf("11/26/2021", "Card Load", "200", "GBP"), result.rows[0])
        assertEquals(listOf("11/27/2021", "Coffee Shop", "-4.5", "GBP"), result.rows[1])
    }

    @Test
    fun sheetNames_multiSheetWorkbook_returnsAllInOrder() {
        val bytes =
            workbookBytes {
                sheet("First") { row("a") }
                sheet("Second") { row("b") }
            }

        assertEquals(listOf("First", "Second"), parser.sheetNames(bytes))
    }

    @Test
    fun parse_namedSheet_selectsThatSheetNotTheFirst() {
        val bytes =
            workbookBytes {
                sheet("First") { row("wrong") }
                sheet("Second") {
                    row("header")
                    row("value")
                }
            }

        val result = parser.parse(bytes, sheetName = "Second")

        assertEquals(listOf("header"), result.headers)
        assertEquals(listOf(listOf("value")), result.rows)
    }

    @Test
    fun parse_unknownSheetName_throws() {
        val bytes = workbookBytes { sheet("Sheet1") { row("a") } }

        assertFailsWith<IllegalArgumentException> { parser.parse(bytes, sheetName = "NoSuchSheet") }
    }

    @Test
    fun parse_emptySheet_returnsEmptyResult() {
        val bytes = workbookBytes { sheet("Sheet1") {} }

        val result = parser.parse(bytes)

        assertEquals(emptyList(), result.headers)
        assertEquals(emptyList(), result.rows)
    }

    @Test
    fun parse_shortRow_padsMissingTrailingCellsAsEmptyStrings() {
        val bytes =
            workbookBytes {
                sheet("Sheet1") {
                    row("a", "b", "c")
                    row("1", "2")
                }
            }

        val result = parser.parse(bytes)

        assertEquals(listOf("1", "2", ""), result.rows[0])
    }

    @Test
    fun parse_dateFormatHidingTheTime_keepsTheTime() {
        val result = parser.parse(dateCellsWorkbook("D/M/YYYY", 43940.45821759259, 44153.0))

        assertEquals(listOf(listOf("19/4/2020 10:59:50"), listOf("18/11/2020")), result.rows)
    }

    @Test
    fun parse_dateFormatShowingTheTime_readsAsShown() {
        val result = parser.parse(dateCellsWorkbook("yyyy-mm-dd hh:mm", 43940.45821759259))

        assertEquals(listOf(listOf("2020-04-19 10:59")), result.rows)
    }

    @Test
    fun parse_numberCell_keepsItsFraction() {
        val bytes =
            XSSFWorkbook().use { workbook ->
                val sheet = workbook.createSheet("Sheet1")
                sheet.createRow(0).createCell(0).setCellValue("amount")
                sheet.createRow(1).createCell(0).setCellValue(43940.25)
                ByteArrayOutputStream().also { workbook.write(it) }.toByteArray()
            }

        assertEquals(listOf(listOf("43940.25")), parser.parse(bytes).rows)
    }

    private fun dateCellsWorkbook(
        format: String,
        vararg serials: Double,
    ): ByteArray =
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Sheet1")
            sheet.createRow(0).createCell(0).setCellValue("date")
            val style = workbook.createCellStyle().apply { dataFormat = workbook.createDataFormat().getFormat(format) }
            serials.forEachIndexed { index, serial ->
                val cell = sheet.createRow(index + 1).createCell(0)
                cell.setCellValue(serial)
                cell.cellStyle = style
            }
            ByteArrayOutputStream().also { workbook.write(it) }.toByteArray()
        }

    private class WorkbookBuilder(
        private val workbook: XSSFWorkbook,
    ) {
        fun sheet(
            name: String,
            block: SheetBuilder.() -> Unit,
        ) {
            SheetBuilder(workbook.createSheet(name)).block()
        }
    }

    private class SheetBuilder(
        private val sheet: org.apache.poi.ss.usermodel.Sheet,
    ) {
        private var rowIndex = 0

        fun row(vararg values: String) {
            val row = sheet.createRow(rowIndex++)
            values.forEachIndexed { columnIndex, value -> row.createCell(columnIndex).setCellValue(value) }
        }
    }

    private fun workbookBytes(block: WorkbookBuilder.() -> Unit): ByteArray {
        val workbook = XSSFWorkbook()
        WorkbookBuilder(workbook).block()
        val out = ByteArrayOutputStream()
        workbook.use { it.write(out) }
        return out.toByteArray()
    }
}
