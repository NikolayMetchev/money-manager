package com.moneymanager.ui.components.imports

import com.moneymanager.tasks.Progress
import kotlin.test.Test
import kotlin.test.assertEquals

class BulkImportProgressIndicatorTest {
    @Test
    fun linesSayFilesRowsTheFileAndItsStep() {
        val progress =
            Progress(
                detail = "File 3 of 8 — statement.csv: Importing transactions",
                fraction = 0.7125f,
                processed = 127_500,
                total = 300_000,
                item = Progress.Item(2, 8, "files", "statement.csv", "Importing transactions"),
            )

        assertEquals(
            listOf("2 of 8 files (25%)", "127.5K of 300K rows (42%)", "statement.csv", "Importing transactions"),
            bulkProgressLines(progress),
        )
    }

    @Test
    fun withoutCountsTheLinesStayButAreBlank() {
        assertEquals(listOf("", "", "", "Starting"), bulkProgressLines(Progress("Starting")))
    }
}
