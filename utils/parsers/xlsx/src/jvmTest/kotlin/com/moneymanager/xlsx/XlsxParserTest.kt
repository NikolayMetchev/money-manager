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
