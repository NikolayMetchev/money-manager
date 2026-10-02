package com.moneymanager.csvimporter

import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.CsvRowLink
import com.moneymanager.domain.model.csv.ImportStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CsvRedownloadPlannerTest {
    private fun staged(headers: List<String>) =
        CsvImport(
            id = CsvImportId(Uuid.random()),
            tableName = "csv_import_x",
            originalFileName = "statement.csv",
            importTimestamp = Instant.fromEpochMilliseconds(0),
            rowCount = 0,
            columnCount = headers.size,
            columns = headers.mapIndexed { i, name -> CsvColumn(CsvColumnId(Uuid.random()), i, name) },
            deviceInfo = DeviceInfo.Jvm("os", "machine"),
            fileChecksum = "old",
            fileLastModified = Instant.fromEpochMilliseconds(0),
        )

    private fun row(
        index: Long,
        values: List<String>,
        status: ImportStatus? = null,
        transfer: Long? = null,
    ) = CsvRow(rowIndex = index, values = values, transferId = transfer?.let(::TransferId), importStatus = status)

    private val headers = listOf("Date", "Description", "Amount")

    @Test
    fun `an identical parse is unchanged`() {
        val old = listOf(row(1, listOf("2024-01-01", "Coffee", "-3.50"), ImportStatus.IMPORTED, 7))
        val plan = planCsvRedownload(staged(headers), old, headers, listOf(listOf("2024-01-01", "Coffee", "-3.50")), checksum = "old")
        assertEquals(CsvRedownloadPlan.Unchanged, plan)
    }

    @Test
    fun `an added column keeps every imported row's outcome and link`() {
        val old =
            listOf(
                row(1, listOf("2024-01-01", "Coffee", "-3.50"), ImportStatus.IMPORTED, 7),
                row(2, listOf("2024-01-02", "Lunch", "-12.00"), ImportStatus.DUPLICATE, 8),
            )
        val newHeaders = headers + "Category"
        val newRows = listOf(listOf("2024-01-01", "Coffee", "-3.50", "Eating out"), listOf("2024-01-02", "Lunch", "-12.00", "Eating out"))

        val plan = assertIs<CsvRedownloadPlan.Replace>(planCsvRedownload(staged(headers), old, newHeaders, newRows, checksum = "new"))

        assertEquals(mapOf(1L to 1L, 2L to 2L), plan.rowIndexRemap)
        assertEquals(
            listOf(CsvRowLink(1, ImportStatus.IMPORTED, TransferId(7)), CsvRowLink(2, ImportStatus.DUPLICATE, TransferId(8))),
            plan.carriedRows,
        )
        assertEquals(listOf("Category"), plan.addedColumns)
        assertEquals(0, plan.newRows)
    }

    @Test
    fun `a removed column and reordered rows still match on what both parses share`() {
        val old =
            listOf(
                row(1, listOf("2024-01-01", "Coffee", "-3.50"), ImportStatus.IMPORTED, 7),
                row(2, listOf("2024-01-02", "Lunch", "-12.00"), ImportStatus.IMPORTED, 8),
            )
        val newHeaders = listOf("Amount", "Date")
        val newRows = listOf(listOf("-12.00", "2024-01-02"), listOf("-3.50", "2024-01-01"), listOf("-1.00", "2024-01-03"))

        val plan = assertIs<CsvRedownloadPlan.Replace>(planCsvRedownload(staged(headers), old, newHeaders, newRows, checksum = "new"))

        assertEquals(mapOf(2L to 1L, 1L to 2L), plan.rowIndexRemap)
        assertEquals(listOf("Description"), plan.removedColumns)
        assertEquals(1, plan.newRows, "a row the file gained arrives never-imported")
    }

    @Test
    fun `identical rows pair up in order`() {
        val coffee = listOf("2024-01-01", "Coffee", "-3.50")
        val old = listOf(row(1, coffee, ImportStatus.IMPORTED, 7), row(2, coffee, ImportStatus.IMPORTED, 8))

        val plan =
            assertIs<CsvRedownloadPlan.Replace>(planCsvRedownload(staged(headers), old, headers, listOf(coffee, coffee), checksum = "new"))

        assertEquals(listOf(TransferId(7), TransferId(8)), plan.carriedRows.map { it.transferId })
    }

    @Test
    fun `an imported row the file no longer has blocks the re-download`() {
        val old =
            listOf(
                row(1, listOf("2024-01-01", "Coffee", "-3.50"), ImportStatus.IMPORTED, 7),
                row(2, listOf("2024-01-02", "Lunch", "-12.00"), ImportStatus.IMPORTED, 8),
            )

        val plan =
            assertIs<CsvRedownloadPlan.Blocked>(
                planCsvRedownload(staged(headers), old, headers, listOf(listOf("2024-01-01", "Coffee", "-3.50")), checksum = "new"),
            )

        assertEquals(listOf(2L), plan.unmatchedImportedRows.map { it.rowIndex })
    }

    @Test
    fun `a never-imported file is replaced even when no column survives`() {
        // A Bybit export staged before the parser skipped its "UID: …" preamble line.
        val stale = staged(listOf("UID: 16314371", "Company Name: ", "Country: "))
        val old = listOf(row(1, listOf("Uid", "Type", "Coin")), row(2, listOf("16314371", "trade", "USDT"), ImportStatus.ERROR))
        val newHeaders = listOf("Uid", "Type", "Coin", "Amount", "Wallet Balance", "Time(UTC)")

        val plan =
            assertIs<CsvRedownloadPlan.Replace>(
                planCsvRedownload(stale, old, newHeaders, listOf(listOf("16314371", "trade", "USDT", "-1", "0", "2022-01-05 13:18:18"))),
            )

        assertEquals(emptyList(), plan.carriedRows, "an errored row carries nothing")
        assertEquals(2, plan.droppedRows)
    }
}
