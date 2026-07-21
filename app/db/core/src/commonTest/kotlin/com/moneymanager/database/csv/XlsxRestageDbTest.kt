@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.csv

import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies the in-place re-staging of an Excel import: [restageImport] replaces the staged rows/columns
 * with a different worksheet's data (keeping the import id and its stored workbook bytes), which is what
 * lets the applier switch to the sheet a matched strategy actually targets.
 */
class XlsxRestageDbTest : DbTest() {
    private val lastModified = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val workbookBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4) // arbitrary stand-in for a .xlsx

    @Test
    fun restageReplacesSheetRowsColumnsAndBlobWorksheet() =
        runTest {
            val repo = repositories.csvImportRepository

            val sheet1Headers = listOf("Date", "Amount")
            val sheet1Rows = listOf(listOf("2024-01-01", "10.00"), listOf("2024-01-02", "20.00"))
            val id =
                repo.createImport(
                    fileName = "book.xlsx",
                    headers = sheet1Headers,
                    rows = sheet1Rows,
                    fileChecksum = "checksum",
                    fileLastModified = lastModified,
                    xlsxBytes = workbookBytes,
                    xlsxWorksheetName = "Sheet1",
                )

            // Staged from the first sheet.
            val staged = repo.getImport(id).first()!!
            assertEquals(listOf("Date", "Amount"), staged.columns.map { it.originalName })
            assertEquals(2L, staged.rowCount.toLong())
            assertEquals("Sheet1", repo.getXlsxBlob(id)!!.worksheetName)

            // Re-stage the second sheet: different column shape and rows.
            val sheet2Headers = listOf("Timestamp", "Description", "Value")
            val sheet2Rows =
                listOf(
                    listOf("2024-02-01", "Coffee", "3.50"),
                    listOf("2024-02-02", "Lunch", "12.00"),
                    listOf("2024-02-03", "Books", "40.00"),
                )
            repo.restageImport(id, sheet2Headers, sheet2Rows, "Sheet2")

            val restaged = repo.getImport(id).first()!!
            assertEquals(id, restaged.id, "id must stay stable across re-staging")
            assertEquals(listOf("Timestamp", "Description", "Value"), restaged.columns.map { it.originalName })
            assertEquals(3L, restaged.rowCount.toLong())

            val rows = repo.getImportRows(id, limit = 10, offset = 0)
            assertEquals(3, rows.size)
            assertContentEquals(listOf("2024-02-01", "Coffee", "3.50"), rows[0].values)
            assertContentEquals(listOf("2024-02-03", "Books", "40.00"), rows[2].values)

            // The blob records the new worksheet but keeps the original workbook bytes.
            val blob = repo.getXlsxBlob(id)!!
            assertEquals("Sheet2", blob.worksheetName)
            assertTrue(blob.fileBytes.contentEquals(workbookBytes), "workbook bytes must be preserved")
        }
}
