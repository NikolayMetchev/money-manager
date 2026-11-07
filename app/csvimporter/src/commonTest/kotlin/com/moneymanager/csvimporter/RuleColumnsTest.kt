package com.moneymanager.csvimporter

import com.moneymanager.domain.model.csvstrategy.FILE_NAME_COLUMN
import kotlin.test.Test
import kotlin.test.assertEquals

class RuleColumnsTest {
    private val index = mapOf("A" to 0, "B" to 1, FILE_NAME_COLUMN to 2, "Set by a rule" to 3)

    @Test
    fun `the file name follows the row's cells and leaves a rule's column absent`() {
        assertEquals(listOf("a", "b", "file.csv"), listOf("a", "b").withFileName(index, "file.csv"))
    }

    @Test
    fun `a short row keeps the file name in its column`() {
        assertEquals(listOf("a", "", "file.csv"), listOf("a").withFileName(index, "file.csv"))
    }

    @Test
    fun `no file name leaves the row as it is`() {
        assertEquals(listOf("a", "b"), listOf("a", "b").withFileName(index, null))
    }

    @Test
    fun `a file with a column of that name keeps its own cell`() {
        val own = mapOf("A" to 0, FILE_NAME_COLUMN to 1)
        assertEquals(listOf("a", "its own"), listOf("a", "its own").withFileName(own, "file.csv"))
    }
}
