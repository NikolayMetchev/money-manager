package com.moneymanager.database

import com.moneymanager.tasks.Progress
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val FILE_COUNTER = Regex("""^File (\d+) of (\d+) — """)

/**
 * Asserts the contract every bulk import promises its progress bar: at least one emission, fractions
 * within 0..1 and never decreasing within a phase (each phase fills the bar from empty, in order), rows
 * counted in the last phase (earlier ones, preparing, have none), a terminal 100% with all rows counted,
 * and every one of the [filesTotal] files handed to the bulk call announced by the file counter in the detail.
 */
fun assertBulkProgress(
    progress: List<Progress>,
    filesTotal: Int,
) {
    assertTrue(progress.isNotEmpty(), "bulk import should emit progress")
    progress.forEach {
        val fraction = requireNotNull(it.fraction) { "bulk progress should always carry a fraction" }
        assertTrue(fraction in 0f..1f, "fraction $fraction out of range")
        val phase = it.phase
        if (phase == null || phase.number == phase.count) {
            val rowsTotal = requireNotNull(it.total) { "bulk progress should carry a row total in its last phase" }
            assertTrue(it.processed in 0..rowsTotal, "rows done ${it.processed} out of 0..$rowsTotal")
        }
    }
    progress.zipWithNext().forEach { (previous, next) ->
        val previousPhase = previous.phase?.number ?: 0
        val nextPhase = next.phase?.number ?: 0
        assertTrue(nextPhase >= previousPhase, "phases went backwards: $previousPhase -> $nextPhase")
        if (nextPhase == previousPhase) {
            assertTrue(next.fraction!! >= previous.fraction!!, "progress went backwards: ${previous.fraction} -> ${next.fraction}")
            if (previous.processed != null && next.processed != null) {
                assertTrue(
                    next.processed!! >= previous.processed!!,
                    "row counter went backwards: ${previous.processed} -> ${next.processed}",
                )
            }
        }
    }
    val counters = progress.mapNotNull { FILE_COUNTER.find(it.detail)?.destructured }
    assertTrue(counters.all { (_, total) -> total.toInt() == filesTotal }, "file counter should count $filesTotal files")
    assertEquals((1..filesTotal).toSet(), counters.mapTo(mutableSetOf()) { (index, _) -> index.toInt() })
    assertEquals(1f, progress.last().fraction, "bulk import should end at 100%")
    assertEquals(progress.last().total, progress.last().processed, "bulk import should end with all rows counted")
}
