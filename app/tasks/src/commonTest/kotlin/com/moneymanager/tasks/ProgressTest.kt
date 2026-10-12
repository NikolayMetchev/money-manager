package com.moneymanager.tasks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProgressTest {
    @Test
    fun countedProgressDerivesItsFraction() {
        val progress = Progress.counted("Downloading", 1, 4)

        assertEquals("Downloading", progress.detail)
        assertEquals(0.25f, progress.fraction)
        assertEquals(1, progress.processed)
        assertEquals(4, progress.total)
    }

    @Test
    fun countedProgressHasNoFractionUntilTheTotalIsKnown() {
        val progress = Progress.counted("Listing", 0, 0)

        assertNull(progress.fraction)
        assertEquals(0, progress.total)
    }

    @Test
    fun formatElapsedTimeFormatsExpectedRanges() {
        assertEquals("00:00", formatElapsedTime(0.milliseconds))
        assertEquals("00:00", formatElapsedTime((-5).seconds))
        assertEquals("01:05", formatElapsedTime(65.seconds))
        assertEquals("1:01:01", formatElapsedTime(3661.seconds))
    }
}
