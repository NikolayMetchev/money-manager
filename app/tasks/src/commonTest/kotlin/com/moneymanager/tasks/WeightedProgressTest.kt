package com.moneymanager.tasks

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeightedProgressTest {
    private val emitted = mutableListOf<Progress>()

    private fun tracker(weights: List<Int>) = WeightedProgress(weights) { emitted += it }

    @Test
    fun weightsItemsByRowCount() =
        runTest {
            val tracker = tracker(listOf(100, 300))

            tracker.itemStarted(0, "a.csv")
            tracker.item(0, "a.csv", Progress("Importing transactions", fraction = 0.5f))
            tracker.itemStarted(1, "b.csv")
            tracker.item(1, "b.csv", Progress("Importing transactions", fraction = 0.5f))
            tracker.done()

            // a.csv is 1/4 of the run: half of it is 1/8; b.csv half-done adds half of its 3/4 share.
            assertEquals(listOf(0f, 0.125f, 0.25f, 0.625f, 1f), emitted.map { it.fraction })
            assertEquals(listOf(0, 50, 100, 250, 400), emitted.map { it.processed })
            assertTrue(emitted.all { it.total == 400 })
            assertEquals(
                listOf(
                    "File 1 of 2 — a.csv",
                    "File 1 of 2 — a.csv: Importing transactions",
                    "File 2 of 2 — b.csv",
                    "File 2 of 2 — b.csv: Importing transactions",
                    "Finishing up",
                ),
                emitted.map { it.detail },
            )
        }

    @Test
    fun preparationComesFirstAndCreditsStepsAsTheyFinish() =
        runTest {
            val tracker =
                WeightedProgress(listOf(100, 100), preparation = listOf(100, 300), preparationNoun = "PDFs") { emitted += it }

            // The steps run in parallel, so the second can finish first.
            tracker.prepared(1, "Staged", "b.pdf")
            tracker.prepared(0, "Staged", "a.pdf")
            tracker.itemStarted(0, "a.pdf")
            tracker.item(0, "a.pdf", Progress("Importing transactions", fraction = 0.5f))

            assertEquals(listOf(0.5f, 4f / 6, 4f / 6, 0.75f), emitted.map { it.fraction })
            // Preparation takes its share of the bar but isn't counted as rows.
            assertTrue(emitted.all { it.total == 200 })
            assertEquals(150, emitted.last().processed)
            assertEquals(
                listOf("Staged 1 of 2 PDFs — b.pdf", "Staged 2 of 2 PDFs — a.pdf"),
                emitted.take(2).map { it.detail },
            )
        }

    @Test
    fun clampsMultiPhaseSweepsMonotonic() =
        runTest {
            val tracker = tracker(listOf(10, 10))

            tracker.itemStarted(0, "a.csv")
            tracker.item(0, "a.csv", Progress("Updating transactions", fraction = 1f))
            // A second 0..1 sweep within the same file must not move the bar backwards.
            tracker.item(0, "a.csv", Progress("Importing transactions", fraction = 0.2f))
            tracker.itemStarted(1, "b.csv")

            assertTrue(emitted.zipWithNext().all { (previous, next) -> next.fraction!! >= previous.fraction!! })
            assertTrue(emitted.zipWithNext().all { (previous, next) -> next.processed!! >= previous.processed!! })
            assertEquals(0.5f, emitted.last().fraction)
            assertEquals(10, emitted.last().processed)
        }

    @Test
    fun skippedItemsStillAdvanceTheBar() =
        runTest {
            val tracker = tracker(listOf(10, 10))

            tracker.itemStarted(0, "a.csv")
            // File 0 is skipped (no strategy), so starting file 1 must credit file 0's full weight.
            tracker.itemStarted(1, "b.csv")

            assertEquals(0.5f, emitted.last().fraction)
            assertEquals("File 2 of 2 — b.csv", emitted.last().detail)
        }

    @Test
    fun nullFractionHoldsAtItemStart() =
        runTest {
            val tracker = tracker(listOf(10, 10))

            tracker.itemStarted(0, "a.csv")
            tracker.item(0, "a.csv", Progress("Loading rows"))

            assertEquals(0f, emitted.last().fraction)
            assertEquals("File 1 of 2 — a.csv: Loading rows", emitted.last().detail)
        }

    @Test
    fun zeroTotalRowsNeverDividesByZero() =
        runTest {
            val tracker = tracker(listOf(0, 0))

            tracker.started()
            tracker.itemStarted(0, "a.csv")
            tracker.itemStarted(1, "b.csv")
            tracker.done()

            assertTrue(emitted.all { it.fraction!! in 0f..1f })
            assertEquals(1f, emitted.last().fraction)
            assertTrue(emitted.all { it.total == 0 && it.processed == 0 })
        }

    @Test
    fun theItemNounIsConfigurable() =
        runTest {
            WeightedProgress(listOf(1), itemNoun = "Session") { emitted += it }.itemStarted(0, "#7")

            assertEquals("Session 1 of 1 — #7", emitted.single().detail)
        }
}
