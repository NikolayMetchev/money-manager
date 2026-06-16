package com.moneymanager.qifimporter

import com.moneymanager.domain.model.qif.QifImportRecord
import com.moneymanager.domain.model.qif.QifRecordSplit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QifCsvAdapterTest {
    private fun record(
        index: Long,
        amount: String? = null,
        category: String? = null,
        transferAccount: String? = null,
        splits: List<QifRecordSplit> = emptyList(),
        supported: Boolean = true,
        sectionType: String = "BANK",
    ) = QifImportRecord(
        recordIndex = index,
        sectionType = sectionType,
        accountName = "Checking",
        supported = supported,
        rawText = "",
        date = "01/01/2022",
        amount = amount,
        payee = "Payee",
        memo = "Memo",
        category = category,
        transferAccount = transferAccount,
        splits = splits,
    )

    @Test
    fun headers_andColumns_alignByIndex() {
        assertEquals(QifCsvAdapter.headers.size, QifCsvAdapter.columns.size)
        QifCsvAdapter.columns.forEachIndexed { index, column ->
            assertEquals(index, column.columnIndex)
            assertEquals(QifCsvAdapter.headers[index], column.originalName)
        }
    }

    @Test
    fun toRows_nonSplitRecord_producesSingleRowWithRecordIndex() {
        val rows = QifCsvAdapter.toRows(listOf(record(index = 3, amount = "-10.00", category = "Food")))

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(3, row.rowIndex)
        assertEquals("-10.00", row.values[QifCsvAdapter.columns.first { it.originalName == QifCsvAdapter.COL_AMOUNT }.columnIndex])
        assertEquals("Food", row.values[QifCsvAdapter.columns.first { it.originalName == QifCsvAdapter.COL_CATEGORY }.columnIndex])
    }

    @Test
    fun toRows_splitRecord_producesOneRowPerSplitSharingRecordIndex() {
        val rows =
            QifCsvAdapter.toRows(
                listOf(
                    record(
                        index = 5,
                        amount = "-90.00",
                        splits =
                            listOf(
                                QifRecordSplit(category = "Groceries", memo = "Food", amount = "-60.00"),
                                QifRecordSplit(transferAccount = "Cash", amount = "-30.00"),
                            ),
                    ),
                ),
            )

        assertEquals(2, rows.size)
        assertTrue(rows.all { it.rowIndex == 5L })
        val amountIdx = QifCsvAdapter.columns.first { it.originalName == QifCsvAdapter.COL_AMOUNT }.columnIndex
        val transferIdx = QifCsvAdapter.columns.first { it.originalName == QifCsvAdapter.COL_TRANSFER_ACCOUNT }.columnIndex
        assertEquals(listOf("-60.00", "-30.00"), rows.map { it.values[amountIdx] })
        assertEquals("Cash", rows[1].values[transferIdx])
    }

    @Test
    fun toRows_unsupportedRecords_areSkipped() {
        val rows =
            QifCsvAdapter.toRows(
                listOf(
                    record(index = 0, amount = "-1.00"),
                    record(index = 1, amount = "100.00", supported = false, sectionType = "INVESTMENT"),
                ),
            )

        assertEquals(1, rows.size)
        assertEquals(0, rows.single().rowIndex)
    }
}
