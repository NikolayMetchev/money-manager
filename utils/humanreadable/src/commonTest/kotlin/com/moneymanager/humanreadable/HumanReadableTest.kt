package com.moneymanager.humanreadable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HumanReadableTest {
    @Test
    fun fileSize_belowOneKibibyte_isWholeBytes() {
        assertEquals("0 B", HumanReadable.fileSize(0))
        assertEquals("1023 B", HumanReadable.fileSize(1_023))
    }

    @Test
    fun fileSize_scalesByPowersOf1024WithOneDecimal() {
        assertEquals("1.0 kB", HumanReadable.fileSize(1_024))
        assertEquals("1.5 kB", HumanReadable.fileSize(1_536))
        assertEquals("3.5 MB", HumanReadable.fileSize(3_670_016))
        assertEquals("2.0 GB", HumanReadable.fileSize(2L * 1_024 * 1_024 * 1_024))
        assertEquals("1.0 TB", HumanReadable.fileSize(1_024L * 1_024 * 1_024 * 1_024))
    }

    @Test
    fun fileSize_groupsThousandsAndHonoursDecimals() {
        assertEquals("1,024.0 TB", HumanReadable.fileSize(1_024L * 1_024 * 1_024 * 1_024 * 1_024))
        assertEquals("2 kB", HumanReadable.fileSize(1_536, decimals = 0))
        assertEquals("1.50 kB", HumanReadable.fileSize(1_536, decimals = 2))
    }

    @Test
    fun duration_picksLargestUnitAndPluralises() {
        assertEquals("0 seconds", HumanReadable.duration(0.seconds))
        assertEquals("1 second", HumanReadable.duration(1.seconds))
        assertEquals("59 seconds", HumanReadable.duration(59.seconds))
        assertEquals("1 minute", HumanReadable.duration(60.seconds))
        assertEquals("59 minutes", HumanReadable.duration(59.minutes + 59.seconds))
        assertEquals("1 hour", HumanReadable.duration(1.hours))
        assertEquals("23 hours", HumanReadable.duration(23.hours + 59.minutes))
        assertEquals("1 day", HumanReadable.duration(1.days))
        assertEquals("6 days", HumanReadable.duration(6.days + 23.hours))
    }

    @Test
    fun duration_roundsWeeksMonthsAndYears() {
        assertEquals("1 week", HumanReadable.duration(7.days))
        assertEquals("1 week", HumanReadable.duration(10.days + 12.hours))
        assertEquals("2 weeks", HumanReadable.duration(11.days))
        assertEquals("4 weeks", HumanReadable.duration(29.days))
        assertEquals("1 month", HumanReadable.duration(30.days))
        assertEquals("11 months", HumanReadable.duration(340.days))
        // 360 days rounds to 12 months but is still under a year, so it stays in months
        assertEquals("12 months", HumanReadable.duration(360.days))
        assertEquals("1 year", HumanReadable.duration(365.days))
        assertEquals("3 years", HumanReadable.duration(1_100.days))
    }

    @Test
    fun timeAgo_pastFutureAndNow() {
        val now = Instant.fromEpochSeconds(1_700_000_000)
        assertEquals("now", HumanReadable.timeAgo(now, now))
        assertEquals("now", HumanReadable.timeAgo(now - 1.seconds, now))
        assertEquals("now", HumanReadable.timeAgo(now + 500.milliseconds, now))
        assertEquals("2 seconds ago", HumanReadable.timeAgo(now - 2.seconds, now))
        assertEquals("3 days ago", HumanReadable.timeAgo(now - 3.days, now))
        assertEquals("in 1 second", HumanReadable.timeAgo(now + 1.seconds, now))
        assertEquals("in 2 hours", HumanReadable.timeAgo(now + 2.hours, now))
    }
}
