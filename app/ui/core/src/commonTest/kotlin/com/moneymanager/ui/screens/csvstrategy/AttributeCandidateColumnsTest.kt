@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens.csvstrategy

import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class AttributeCandidateColumnsTest {
    @Test
    fun `attribute candidates include fallback columns`() {
        val columns =
            listOf(
                csvColumn(0, "Date"),
                csvColumn(1, "Description"),
                csvColumn(2, "Amount"),
                csvColumn(3, "Name"),
                csvColumn(4, "Type"),
            )

        val candidates =
            attributeCandidateColumns(
                csvColumns = columns,
                primaryFieldColumnNames = setOf("Date", "Description", "Amount", "Name"),
            )

        assertEquals(listOf("Type"), candidates.map { it.originalName })
    }

    @Test
    fun `attribute candidates exclude primary field columns`() {
        val columns =
            listOf(
                csvColumn(0, "Date"),
                csvColumn(1, "Description"),
                csvColumn(2, "Amount"),
            )

        val candidates =
            attributeCandidateColumns(
                csvColumns = columns,
                primaryFieldColumnNames = setOf("Date", "Description", "Amount"),
            )

        assertEquals(emptyList(), candidates)
    }

    private fun csvColumn(
        index: Int,
        name: String,
    ): CsvColumn = CsvColumn(CsvColumnId(Uuid.random()), index, name)
}
