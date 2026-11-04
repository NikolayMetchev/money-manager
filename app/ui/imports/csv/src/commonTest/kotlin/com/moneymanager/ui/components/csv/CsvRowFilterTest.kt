package com.moneymanager.ui.components.csv

import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CsvRowFilterTest {
    private val rows =
        listOf(
            CsvRow(0, listOf("Coffee", "GBP"), importStatus = ImportStatus.IMPORTED),
            CsvRow(1, listOf("Rent", "GBP"), importStatus = ImportStatus.DUPLICATE),
            CsvRow(2, listOf("Coffee", "EUR"), importStatus = ImportStatus.DUPLICATE),
            CsvRow(3, listOf("", "USD")),
        )

    @Test
    fun `an empty filter keeps every row`() {
        assertFalse(CsvRowFilter().isActive)
        assertEquals(rows, CsvRowFilter().apply(rows))
    }

    @Test
    fun `ticked values of one column keep rows with any of them`() {
        val filter = CsvRowFilter().withColumn(0, setOf("Coffee", "Rent"))
        assertEquals(listOf(0L, 1L, 2L), filter.apply(rows).map { it.rowIndex })
    }

    @Test
    fun `columns combine with and`() {
        val filter = CsvRowFilter().withColumn(0, setOf("Coffee")).withColumn(1, setOf("EUR"))
        assertEquals(listOf(2L), filter.apply(rows).map { it.rowIndex })
    }

    @Test
    fun `a blank value can be ticked and status filters too`() {
        assertEquals(listOf(3L), CsvRowFilter().withColumn(0, setOf("")).apply(rows).map { it.rowIndex })
        val byStatus = CsvRowFilter(statuses = setOf(ImportStatus.DUPLICATE))
        assertEquals(listOf(1L, 2L), byStatus.apply(rows).map { it.rowIndex })
    }

    @Test
    fun `unticking everything in a column removes its filter`() {
        val filter = CsvRowFilter().withColumn(0, setOf("Coffee")).withColumn(0, emptySet())
        assertFalse(filter.isActive)
    }

    @Test
    fun `distinct values are sorted and include blank`() {
        assertEquals(listOf("", "Coffee", "Rent"), distinctColumnValues(rows, 0))
        assertTrue(distinctColumnValues(rows, 5) == listOf(""))
    }
}
