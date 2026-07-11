package com.moneymanager.csvimporter

import com.moneymanager.importengineapi.ImportProgress
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BulkProgressTrackerTest {
    private val emitted = mutableListOf<BulkImportProgress>()

    private fun tracker(weights: List<Int>) = BulkProgressTracker(weights) { emitted += it }

    @Test
    fun weightsFilesByRowCount() =
        runTest {
            val tracker = tracker(listOf(100, 300))

            tracker.fileStarted(0, "a.csv")
            tracker.phase(0, "a.csv", ImportProgress("Importing transactions", fraction = 0.5f))
            tracker.fileStarted(1, "b.csv")
            tracker.phase(1, "b.csv", ImportProgress("Importing transactions", fraction = 0.5f))
            tracker.done()

            // a.csv is 1/4 of the run: half of it is 1/8; b.csv half-done adds half of its 3/4 share.
            assertEquals(listOf(0f, 0.125f, 0.25f, 0.625f, 1f), emitted.map { it.overallFraction })
            assertEquals(listOf(0L, 50L, 100L, 250L, 400L), emitted.map { it.rowsDone })
            assertTrue(emitted.all { it.rowsTotal == 400L })
            assertEquals(2, emitted.last().filesDone)
            assertEquals(2, emitted.last().filesTotal)
        }

    @Test
    fun clampsMultiPhaseSweepsMonotonic() =
        runTest {
            val tracker = tracker(listOf(10, 10))

            tracker.fileStarted(0, "a.csv")
            tracker.phase(0, "a.csv", ImportProgress("Updating transactions", fraction = 1f))
            // A second 0..1 sweep within the same file must not move the bar backwards.
            tracker.phase(0, "a.csv", ImportProgress("Importing transactions", fraction = 0.2f))
            tracker.fileStarted(1, "b.csv")

            assertTrue(emitted.zipWithNext().all { (previous, next) -> next.overallFraction >= previous.overallFraction })
            assertTrue(emitted.zipWithNext().all { (previous, next) -> next.rowsDone >= previous.rowsDone })
            assertEquals(0.5f, emitted.last().overallFraction)
            assertEquals(10L, emitted.last().rowsDone)
        }

    @Test
    fun skippedFilesStillAdvanceTheBar() =
        runTest {
            val tracker = tracker(listOf(10, 10))

            tracker.fileStarted(0, "a.csv")
            // File 0 is skipped (no strategy) — starting file 1 must credit file 0's full weight.
            tracker.fileStarted(1, "b.csv")

            assertEquals(0.5f, emitted.last().overallFraction)
            assertEquals(1, emitted.last().filesDone)
        }

    @Test
    fun nullFractionHoldsAtFileStart() =
        runTest {
            val tracker = tracker(listOf(10, 10))

            tracker.fileStarted(0, "a.csv")
            tracker.phase(0, "a.csv", ImportProgress("Loading rows"))

            assertEquals(0f, emitted.last().overallFraction)
            assertEquals("Loading rows", emitted.last().detail)
            assertEquals("a.csv", emitted.last().currentFileName)
        }

    @Test
    fun zeroTotalRowsNeverDividesByZero() =
        runTest {
            val tracker = tracker(listOf(0, 0))

            tracker.started()
            tracker.fileStarted(0, "a.csv")
            tracker.fileStarted(1, "b.csv")
            tracker.done()

            assertTrue(emitted.all { it.overallFraction in 0f..1f })
            assertEquals(1f, emitted.last().overallFraction)
            assertTrue(emitted.all { it.rowsTotal == 0L && it.rowsDone == 0L })
        }
}
