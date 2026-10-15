package com.moneymanager.cli

import com.github.ajalt.clikt.core.UsageError
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class DateBoundTest {
    private val london = TimeZone.of("Europe/London")

    @Test
    fun aDateStartsAtLocalMidnightAndAToDateIncludesItsDay() {
        // British Summer Time: local midnight is 23:00 UTC the day before.
        assertEquals(Instant.parse("2024-06-30T23:00:00Z"), parseDateBound("2024-07-01", london, through = false))
        assertEquals(Instant.parse("2024-07-01T23:00:00Z"), parseDateBound("2024-07-01", london, through = true))
    }

    @Test
    fun anInstantIsTakenAsIs() {
        assertEquals(Instant.parse("2024-07-01T10:00:00Z"), parseDateBound("2024-07-01T10:00:00Z", london, through = true))
    }

    @Test
    fun anythingElseIsAUsageError() {
        assertFailsWith<UsageError> { parseDateBound("yesterday", london, through = false) }
    }
}
