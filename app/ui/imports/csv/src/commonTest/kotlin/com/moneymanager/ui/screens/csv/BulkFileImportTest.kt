package com.moneymanager.ui.screens.csv

import com.moneymanager.ui.components.imports.importPickedFiles
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BulkFileImportTest {
    @Test
    fun `summarises imported and skipped files`() =
        runTest {
            val outcome =
                importPickedFiles(listOf("a.csv", "b.csv", "c.csv"), fileName = { it }) { file ->
                    file != "b.csv"
                }

            assertEquals("Imported 2 files, skipped 1 already imported", outcome.message)
            assertFalse(outcome.isError)
        }

    @Test
    fun `keeps importing after a failure and reports it`() =
        runTest {
            val staged = mutableListOf<String>()
            val outcome =
                importPickedFiles(listOf("a.csv", "bad.csv", "c.csv"), fileName = { it }) { file ->
                    if (file == "bad.csv") error("boom")
                    staged.add(file)
                    true
                }

            assertEquals(listOf("a.csv", "c.csv"), staged)
            assertEquals("Imported 2 files, 1 failed: bad.csv: boom", outcome.message)
            assertTrue(outcome.isError)
        }

    @Test
    fun `uses the singular form for a single file`() =
        runTest {
            val outcome = importPickedFiles(listOf("a.csv"), fileName = { it }) { true }

            assertEquals("Imported 1 file", outcome.message)
            assertFalse(outcome.isError)
        }
}
