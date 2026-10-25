package com.moneymanager.ui.screens.csv

import com.moneymanager.compose.filepicker.BinaryFilePickerResult
import com.moneymanager.csv.CsvParseResult
import com.moneymanager.csvimporter.sha256Hex
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.tasks.Progress
import com.moneymanager.xlsx.XlsxParser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

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

    @Test
    fun anExcelFileIsStagedFromItsFirstWorksheet() =
        runTest {
            val workbook = BinaryFilePickerResult("budget.xlsx", byteArrayOf(4, 5), null)
            val parser =
                object : XlsxParser {
                    override fun sheetNames(bytes: ByteArray) = listOf("Spending", "Notes")

                    override fun parse(
                        bytes: ByteArray,
                        sheetName: String?,
                    ) = CsvParseResult(listOf("Date", "Amount"), listOf(listOf("2026-01-02", "12.50")))
                }
            val batches = mutableListOf<ImportBatch>()
            val engine =
                object : ImportEngine {
                    override suspend fun import(
                        batch: ImportBatch,
                        onProgress: (suspend (Progress) -> Unit)?,
                        batchSize: Int,
                    ): ImportResult {
                        batches += batch
                        return ImportResult(createdCsvImportIds = mapOf("budget.xlsx" to CsvImportId(Uuid.random())))
                    }
                }

            engine.stageXlsxFile(workbook, parser, "abc", Instant.fromEpochMilliseconds(7))

            assertEquals(
                listOf(
                    CsvImportMutation.Create(
                        key = "budget.xlsx",
                        fileName = "budget.xlsx",
                        headers = listOf("Date", "Amount"),
                        rows = listOf(listOf("2026-01-02", "12.50")),
                        fileChecksum = "abc",
                        fileLastModified = Instant.fromEpochMilliseconds(7),
                        xlsxBytes = workbook.bytes,
                        xlsxWorksheetName = "Spending",
                    ),
                ),
                batches.single().csvImportMutations,
            )
        }
}
