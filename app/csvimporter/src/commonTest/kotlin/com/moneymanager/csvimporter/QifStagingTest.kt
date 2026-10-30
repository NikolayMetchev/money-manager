package com.moneymanager.csvimporter

import com.moneymanager.domain.model.qif.QifColumns
import kotlin.test.Test
import kotlin.test.assertEquals

class QifStagingTest {
    private val amount = QifColumns.headers.indexOf(QifColumns.COL_AMOUNT)
    private val category = QifColumns.headers.indexOf(QifColumns.COL_CATEGORY)
    private val transferAccount = QifColumns.headers.indexOf(QifColumns.COL_TRANSFER_ACCOUNT)
    private val memo = QifColumns.headers.indexOf(QifColumns.COL_MEMO)

    @Test
    fun aTransactionIsOneRowOverTheQifColumns() {
        val parsed = parseQifAsCsv("!Type:Bank\nD01/01/2022\nT-10.00\nPTesco\nMWeekly shop\nLFood\nN42\nCX\n^\n")

        assertEquals(QifColumns.headers, parsed.headers)
        assertEquals(listOf(listOf("01/01/2022", "-10.00", "Tesco", "Weekly shop", "Food", "", "42", "X", "")), parsed.rows)
    }

    @Test
    fun aSplitTransactionIsOneRowPerSplit() {
        val parsed =
            parseQifAsCsv(
                "!Type:Bank\nD01/01/2022\nT-90.00\nPShop\nMWhole\nSGroceries\nEFood\n$-60.00\nS[Cash]\n$-30.00\n^\n",
            )

        assertEquals(listOf("-60.00", "-30.00"), parsed.rows.map { it[amount] })
        assertEquals(listOf("Groceries", ""), parsed.rows.map { it[category] })
        assertEquals(listOf("", "Cash"), parsed.rows.map { it[transferAccount] })
        // A split without its own memo keeps the transaction's.
        assertEquals(listOf("Food", "Whole"), parsed.rows.map { it[memo] })
    }

    @Test
    fun recordsThatAreNotTransactionsAreLeftOut() {
        val parsed =
            parseQifAsCsv("!Type:Bank\nD01/01/2022\nT-1.00\nPA\n^\n!Type:Invst\nD02/01/2022\nNBuy\nYACME\nT100.00\n^\n")

        assertEquals(listOf("-1.00"), parsed.rows.map { it[amount] })
    }
}
