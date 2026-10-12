package com.moneymanager.tasks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProgressTest {
    @Test
    fun countedProgressDerivesItsFraction() {
        assertEquals(Progress("Downloading", 0.25f, 1, 4), Progress.counted("Downloading", 1, 4))
    }

    @Test
    fun countedProgressHasNoFractionUntilTheTotalIsKnown() {
        assertEquals(Progress("Listing", null, 0, 0), Progress.counted("Listing", 0, 0))
    }

    @Test
    fun formatElapsedTimeFormatsExpectedRanges() {
        assertEquals("00:00", formatElapsedTime(0.milliseconds))
        assertEquals("00:00", formatElapsedTime((-5).seconds))
        assertEquals("01:05", formatElapsedTime(65.seconds))
        assertEquals("1:01:01", formatElapsedTime(3661.seconds))
    }
}
