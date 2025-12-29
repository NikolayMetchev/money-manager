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
            CsvColumn(CsvColumnId(Uuid.random()), 0, "Date"),
            CsvColumn(CsvColumnId(Uuid.random()), 1, "Description"),
            CsvColumn(CsvColumnId(Uuid.random()), 2, "Amount"),
            CsvColumn(CsvColumnId(Uuid.random()), 3, "Name"),
            CsvColumn(CsvColumnId(Uuid.random()), 4, "Type"),
        )

    @Test
    fun `suggestFallbackColumns returns fallback when primary column is blank in some rows`() {
        val rows =
            listOf(
                // Normal row - Name has value
                CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Payment", "-50.00", "Payee", "Faster payment")),
                // Cheque row - Name is blank, Type has value
                CsvRow(rowIndex = 2, values = listOf("16/12/2024", "Cheque credited", "2.40", "", "Cheque")),
                // Another normal row
                CsvRow(rowIndex = 3, values = listOf("17/12/2024", "Payment 2", "-25.00", "Another Payee", "Card payment")),
            )

        val fallbacks =
            ColumnDetector.suggestFallbackColumns(
                primaryColumn = "Name",
                columns = columns,
                rows = rows,
            )

        // When Name is blank (1 row), all other columns have values
        // Function returns the first column with highest coverage (Date)
        assertEquals(1, fallbacks.size)
        assertTrue(fallbacks[0] in listOf("Date", "Description", "Amount", "Type"))
    }

    @Test
    fun `suggestFallbackColumns returns empty list when no rows have blank primary column`() {
        val rows =
            listOf(
                CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Payment 1", "-50.00", "Payee 1", "Faster payment")),
                CsvRow(rowIndex = 2, values = listOf("16/12/2024", "Payment 2", "-25.00", "Payee 2", "Card payment")),
                CsvRow(rowIndex = 3, values = listOf("17/12/2024", "Payment 3", "-75.00", "Payee 3", "Direct Debit")),
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
                CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Payment", "-50.00", "", "Cheque")),
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
    fun `suggestFallbackColumns picks column with highest coverage`() {
        // Create columns including Notes
        val columnsWithNotes =
            listOf(
                CsvColumn(CsvColumnId(Uuid.random()), 0, "Date"),
                CsvColumn(CsvColumnId(Uuid.random()), 1, "Description"),
                CsvColumn(CsvColumnId(Uuid.random()), 2, "Amount"),
                CsvColumn(CsvColumnId(Uuid.random()), 3, "Name"),
                CsvColumn(CsvColumnId(Uuid.random()), 4, "Type"),
                CsvColumn(CsvColumnId(Uuid.random()), 5, "Notes"),
            )

        val rows =
            listOf(
                // Row with blank Name - Type has value, Notes is blank
                CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Cheque", "2.40", "", "Cheque", "")),
                // Row with blank Name - Type has value, Notes has value
                CsvRow(rowIndex = 2, values = listOf("16/12/2024", "Cheque 2", "5.00", "", "Cheque", "Some note")),
                // Row with blank Name - Type has value, Notes is blank
                CsvRow(rowIndex = 3, values = listOf("17/12/2024", "Cheque 3", "10.00", "", "Cheque", "")),
            )

        val fallbacks =
            ColumnDetector.suggestFallbackColumns(
                primaryColumn = "Name",
                columns = columnsWithNotes,
                rows = rows,
            )

        // All 3 rows have blank Name. Date/Description/Amount/Type have values in all 3.
        // Notes only has value in 1/3 rows.
        // Function returns first column with highest coverage (3), which could be Date, Description, Amount, or Type
        assertEquals(1, fallbacks.size)
        // Notes should NOT be selected since it has lower coverage
        assertTrue(fallbacks[0] != "Notes")
        assertTrue(fallbacks[0] in listOf("Date", "Description", "Amount", "Type"))
    }

    @Test
    fun `suggestFallbackColumns handles all blank fallback columns`() {
        val rows =
            listOf(
                // Row with blank Name and blank Type
                CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Unknown", "10.00", "", "")),
            )

        val fallbacks =
            ColumnDetector.suggestFallbackColumns(
                primaryColumn = "Name",
                columns = columns,
                rows = rows,
            )

        // Type is also blank, but Date, Description, Amount have values
        // The function should return one of those as fallback
        assertEquals(1, fallbacks.size)
        // The first column with a value should be picked (Date has highest coverage)
        assertTrue(fallbacks[0] in listOf("Date", "Description", "Amount"))
    }

    @Test
    fun `suggestFallbackColumns excludes primary column from candidates`() {
        val rows =
            listOf(
                CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Cheque", "2.40", "", "Cheque")),
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
}
