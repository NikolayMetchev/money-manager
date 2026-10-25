package com.moneymanager.ui.screens.csv

import com.moneymanager.compose.filepicker.BinaryFilePickerResult
import com.moneymanager.csvimporter.sha256Hex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class StagedFileMessageTest {
    private val picked = BinaryFilePickerResult("statement.pdf", byteArrayOf(1, 2, 3), Instant.fromEpochMilliseconds(42))

    @Test
    fun aNewFileIsStagedWithItsChecksumAndTime() =
        runTest {
            val staged = mutableListOf<Pair<String, Instant>>()

            val result = stagePickedFile(picked, { false }) { checksum, lastModified -> staged += checksum to lastModified }

            assertEquals(StagedFileMessage("Imported statement.pdf", isError = false), result)
            assertEquals(listOf(sha256Hex(picked.bytes) to Instant.fromEpochMilliseconds(42)), staged)
        }

    @Test
    fun aFileAlreadyImportedIsSkipped() =
        runTest {
            var staged = false

            val result = stagePickedFile(picked, { it == sha256Hex(picked.bytes) }) { _, _ -> staged = true }

            assertEquals(StagedFileMessage("Skipped statement.pdf: already imported", isError = false), result)
            assertEquals(false, staged)
        }

    @Test
    fun aFileThatFailsSaysWhy() =
        runTest {
            val result = stagePickedFile(picked, { false }) { _, _ -> throw IllegalArgumentException("Not a readable PDF") }

            assertEquals(StagedFileMessage("statement.pdf: Not a readable PDF", isError = true), result)
        }
}
