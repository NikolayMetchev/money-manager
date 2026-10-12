package com.moneymanager.tasks

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A phased bar is stitched together from phases that each sweep their own 0..1, so the only thing that
 * makes it readable is the scaling: a phase must stay inside its slice and the bar must never rewind
 * when the next phase starts counting from zero again.
 */
class PhasedProgressTest {
    @Test
    fun `an inner sweep is scaled into its own slice of the bar`() =
        runTest {
            val emitted = mutableListOf<Progress>()
            val bar = PhasedProgress { emitted += it }

            val sink = requireNotNull(bar.sink(base = 0.25f, span = 0.5f))
            sink(Progress("Importing transactions", fraction = 0f, processed = 0, total = 10))
            sink(Progress("Importing transactions", fraction = 0.5f, processed = 5, total = 10))
            sink(Progress("Importing transactions", fraction = 1f, processed = 10, total = 10))

            assertEquals(listOf(0.25f, 0.5f, 0.75f), emitted.map { it.fraction })
            assertEquals(listOf(0, 5, 10), emitted.map { it.processed }, "inner counts pass through untouched")
        }

    @Test
    fun `a later phase starting at zero never rewinds the bar`() =
        runTest {
            val emitted = mutableListOf<Progress>()
            val bar = PhasedProgress { emitted += it }

            requireNotNull(bar.sink(base = 0f, span = 0.5f))(Progress("Deleting", fraction = 1f))
            // A phase whose slice starts behind where the bar already is (an out-of-order or unknown
            // fraction) must hold, not jump back.
            bar.emit(base = 0.2f, detail = "Un-hiding")
            requireNotNull(bar.sink(base = 0.5f, span = 0.5f))(Progress("Re-importing", fraction = 0f))

            assertEquals(listOf(0.5f, 0.5f, 0.5f), emitted.map { it.fraction })
            assertEquals("Un-hiding", emitted[1].detail, "the phase text still updates while the bar holds")
        }

    @Test
    fun `a sink replaces inner wording when the phase is given its own`() =
        runTest {
            val emitted = mutableListOf<Progress>()
            val bar = PhasedProgress { emitted += it }

            val sink = requireNotNull(bar.sink(base = 0f, span = 1f, detail = "Removing session's transactions"))
            sink(Progress("Importing transactions", fraction = 0.5f))

            assertEquals("Removing session's transactions", emitted.single().detail)
        }

    @Test
    fun `a null sink makes every emission a no-op`() =
        runTest {
            val bar = PhasedProgress(null)

            assertEquals(null, bar.sink(base = 0f, span = 1f), "nothing to forward to")
            bar.emit(base = 0.5f, detail = "Ignored")
        }
}
