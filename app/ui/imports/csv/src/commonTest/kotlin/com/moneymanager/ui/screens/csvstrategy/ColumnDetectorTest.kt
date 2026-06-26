@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens.csvstrategy

import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ColumnDetectorTest {
    private val columns =
        listOf(
            CsvColumn(CsvColumnId(Uuid.random()), 0, "Transaction ID"),
            CsvColumn(CsvColumnId(Uuid.random()), 1, "Date"),
            CsvColumn(CsvColumnId(Uuid.random()), 2, "Description"),
            CsvColumn(CsvColumnId(Uuid.random()), 3, "Amount"),
            CsvColumn(CsvColumnId(Uuid.random()), 4, "Name"),
            CsvColumn(CsvColumnId(Uuid.random()), 5, "Type"),
        )

    @Test
    fun `suggestFallbackColumns prefers Type column over others`() {
        val rows =
            listOf(
                // Normal row - Name has value
                CsvRow(rowIndex = 1, values = listOf("tx_001", "15/12/2024", "Payment", "-50.00", "Payee", "Faster payment")),
                // Cheque row - Name is blank, Type has value
                CsvRow(rowIndex = 2, values = listOf("tx_002", "16/12/2024", "Cheque credited", "2.40", "", "Cheque")),
                // Another normal row
                CsvRow(rowIndex = 3, values = listOf("tx_003", "17/12/2024", "Payment 2", "-25.00", "Another Payee", "Card payment")),
            )

        val fallbacks =
            ColumnDetector.suggestFallbackColumns(
                primaryColumn = "Name",
                columns = columns,
                rows = rows,
            )

        // Type should be preferred since it's a semantic column
        assertEquals(1, fallbacks.size)
        assertEquals("Type", fallbacks[0])
    }

    @Test
    fun `suggestFallbackColumns excludes ID columns`() {
        val rows =
            listOf(
                // Name is blank, Transaction ID and Type both have values
                CsvRow(rowIndex = 1, values = listOf("tx_001", "15/12/2024", "Cheque", "2.40", "", "Cheque")),
            )

        val fallbacks =
            ColumnDetector.suggestFallbackColumns(
                primaryColumn = "Name",
                columns = columns,
                rows = rows,
            )

        // Transaction ID should be excluded, Type should be selected
        assertEquals(1, fallbacks.size)
        assertEquals("Type", fallbacks[0])
        assertTrue("Transaction ID" !in fallbacks)
    }

    @Test
    fun `suggestFallbackColumns excludes Date and Amount columns`() {
        // Columns without Type
        val columnsWithoutType =
            listOf(
                CsvColumn(CsvColumnId(Uuid.random()), 0, "Transaction ID"),
                CsvColumn(CsvColumnId(Uuid.random()), 1, "Date"),
                CsvColumn(CsvColumnId(Uuid.random()), 2, "Description"),
                CsvColumn(CsvColumnId(Uuid.random()), 3, "Amount"),
                CsvColumn(CsvColumnId(Uuid.random()), 4, "Name"),
            )

        val rows =
            listOf(
                CsvRow(rowIndex = 1, values = listOf("tx_001", "15/12/2024", "Cheque credited", "2.40", "")),
            )

        val fallbacks =
            ColumnDetector.suggestFallbackColumns(
                primaryColumn = "Name",
                columns = columnsWithoutType,
                rows = rows,
            )

        // Only Description should be selected (ID, Date, Amount are excluded)
        assertEquals(1, fallbacks.size)
        assertEquals("Description", fallbacks[0])
    }

    @Test
    fun `suggestFallbackColumns returns empty list when no rows have blank primary column`() {
        val rows =
            listOf(
                CsvRow(rowIndex = 1, values = listOf("tx_001", "15/12/2024", "Payment 1", "-50.00", "Payee 1", "Faster payment")),
                CsvRow(rowIndex = 2, values = listOf("tx_002", "16/12/2024", "Payment 2", "-25.00", "Payee 2", "Card payment")),
                CsvRow(rowIndex = 3, values = listOf("tx_003", "17/12/2024", "Payment 3", "-75.00", "Payee 3", "Direct Debit")),
            )

        val fallbacks =
            ColumnDetector.suggestFallbackColumns(
                primaryColumn = "Name",
                columns = columns,
                rows = rows,
            )

        assertTrue(fallbacks.isEmpty())
    }

    @Test
    fun `suggestFallbackColumns returns empty list when primary column not found`() {
        val rows =
            listOf(
                CsvRow(rowIndex = 1, values = listOf("tx_001", "15/12/2024", "Payment", "-50.00", "", "Cheque")),
            )

        val fallbacks =
            ColumnDetector.suggestFallbackColumns(
                primaryColumn = "NonExistentColumn",
                columns = columns,
                rows = rows,
            )

        assertTrue(fallbacks.isEmpty())
    }

    @Test
    fun `suggestFallbackColumns returns empty list when rows is empty`() {
        val fallbacks =
            ColumnDetector.suggestFallbackColumns(
                primaryColumn = "Name",
                columns = columns,
                rows = emptyList(),
            )

        assertTrue(fallbacks.isEmpty())
    }

    @Test
    fun `suggestFallbackColumns prefers Type over lower coverage columns`() {
        // Create columns including Notes
        val columnsWithNotes =
            listOf(
                CsvColumn(CsvColumnId(Uuid.random()), 0, "Description"),
                CsvColumn(CsvColumnId(Uuid.random()), 1, "Name"),
                CsvColumn(CsvColumnId(Uuid.random()), 2, "Type"),
                CsvColumn(CsvColumnId(Uuid.random()), 3, "Notes"),
            )

        val rows =
            listOf(
                // Row with blank Name - Type has value, Notes is blank
                CsvRow(rowIndex = 1, values = listOf("Cheque", "", "Cheque", "")),
                // Row with blank Name - Type has value, Notes has value
                CsvRow(rowIndex = 2, values = listOf("Cheque 2", "", "Cheque", "Some note")),
                // Row with blank Name - Type has value, Notes is blank
                CsvRow(rowIndex = 3, values = listOf("Cheque 3", "", "Cheque", "")),
            )

        val fallbacks =
            ColumnDetector.suggestFallbackColumns(
                primaryColumn = "Name",
                columns = columnsWithNotes,
                rows = rows,
            )

        // Type should be preferred even though Description has same coverage
        assertEquals(1, fallbacks.size)
        assertEquals("Type", fallbacks[0])
    }

    @Test
    fun `suggestFallbackColumns handles all blank fallback columns`() {
        // Columns without excluded patterns
        val simpleColumns =
            listOf(
                CsvColumn(CsvColumnId(Uuid.random()), 0, "Description"),
                CsvColumn(CsvColumnId(Uuid.random()), 1, "Name"),
                CsvColumn(CsvColumnId(Uuid.random()), 2, "Type"),
            )

        val rows =
            listOf(
                // Row with blank Name and blank Type
                CsvRow(rowIndex = 1, values = listOf("Unknown", "", "")),
            )

        val fallbacks =
            ColumnDetector.suggestFallbackColumns(
                primaryColumn = "Name",
                columns = simpleColumns,
                rows = rows,
            )

        // Type is also blank, Description has value
        assertEquals(1, fallbacks.size)
        assertEquals("Description", fallbacks[0])
    }

    @Test
    fun `suggestFallbackColumns excludes primary column from candidates`() {
        val rows =
            listOf(
                CsvRow(rowIndex = 1, values = listOf("tx_001", "15/12/2024", "Cheque", "2.40", "", "Cheque")),
            )

        val fallbacks =
            ColumnDetector.suggestFallbackColumns(
                primaryColumn = "Name",
                columns = columns,
                rows = rows,
            )

        // Name should not be in the fallbacks list
        assertTrue("Name" !in fallbacks)
    }

    @Test
    fun `suggestFallbackColumns prefers Category column`() {
        val columnsWithCategory =
            listOf(
                CsvColumn(CsvColumnId(Uuid.random()), 0, "Description"),
                CsvColumn(CsvColumnId(Uuid.random()), 1, "Name"),
                CsvColumn(CsvColumnId(Uuid.random()), 2, "Category"),
            )

        val rows =
            listOf(
                CsvRow(rowIndex = 1, values = listOf("Cheque", "", "Income")),
            )

        val fallbacks =
            ColumnDetector.suggestFallbackColumns(
                primaryColumn = "Name",
                columns = columnsWithCategory,
                rows = rows,
            )

        // Category is a preferred column
        assertEquals(1, fallbacks.size)
        assertEquals("Category", fallbacks[0])
    }
}
