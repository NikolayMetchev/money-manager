package com.moneymanager.csvimporter

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BulkReimportTest {
    private suspend fun run(
        outcomes: List<() -> BulkReimportFileOutcome>,
        stopAfter: Int = Int.MAX_VALUE,
    ): BulkReimportResult {
        var started = 0
        return runBulkReimport(
            imports = outcomes,
            rowCount = { 1 },
            fileName = { "file" },
            logLabel = "test",
            onProgress = {},
            startDetail = null,
            stopRequested = { started >= stopAfter },
            prepare = { onPrepared -> outcomes.indices.forEach { onPrepared(it) } },
            reimportFile = { _, outcome, _, _ ->
                started++
                outcome()
            },
        )
    }

    @Test
    fun `skipped and failing files are counted and do not stop the run`() =
        runTest {
            val result =
                run(
                    listOf(
                        { BulkReimportFileOutcome.NoStrategy },
                        { BulkReimportFileOutcome.NoWork },
                        { error("strategy gone") },
                        { BulkReimportFileOutcome.Reimported(successCount = 2, duplicateCount = 1) },
                    ),
                )

            assertEquals(BulkReimportResult(1, 2, 1, 1, 1), result)
            assertEquals(
                "Re-imported 1 file · 2 imported · 1 duplicates skipped · 1 skipped (no strategy) · 1 failed",
                result.toSummary(),
            )
        }

    @Test
    fun `once stopped the files not yet started are counted`() =
        runTest {
            val reimported = { BulkReimportFileOutcome.Reimported(successCount = 1, duplicateCount = 0) }
            val result = run(listOf(reimported, reimported, reimported), stopAfter = 1)

            assertEquals(1, result.filesImported)
            assertEquals(2, result.filesNotStarted)
        }
}
