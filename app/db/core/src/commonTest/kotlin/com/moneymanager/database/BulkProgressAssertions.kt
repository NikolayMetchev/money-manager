package com.moneymanager.database

import com.moneymanager.csvimporter.BulkImportProgress
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Asserts the contract every bulk import promises its progress bar: at least one emission, fractions
 * within 0..1 and never decreasing, a terminal 100% with all files counted, and [filesTotal] matching
 * the number of files handed to the bulk call.
 */
fun assertBulkProgress(
    progress: List<BulkImportProgress>,
    filesTotal: Int,
) {
    assertTrue(progress.isNotEmpty(), "bulk import should emit progress")
    progress.forEach {
        assertEquals(filesTotal, it.filesTotal)
        assertTrue(it.overallFraction in 0f..1f, "fraction ${it.overallFraction} out of range")
        assertTrue(it.rowsDone in 0..it.rowsTotal, "rowsDone ${it.rowsDone} out of 0..${it.rowsTotal}")
    }
    progress.zipWithNext().forEach { (previous, next) ->
        assertTrue(
            next.overallFraction >= previous.overallFraction,
            "progress went backwards: ${previous.overallFraction} -> ${next.overallFraction}",
        )
        assertTrue(
            next.rowsDone >= previous.rowsDone,
            "row counter went backwards: ${previous.rowsDone} -> ${next.rowsDone}",
        )
    }
    assertEquals(1f, progress.last().overallFraction, "bulk import should end at 100%")
    assertEquals(filesTotal, progress.last().filesDone)
    assertEquals(progress.last().rowsTotal, progress.last().rowsDone, "bulk import should end with all rows counted")
}
