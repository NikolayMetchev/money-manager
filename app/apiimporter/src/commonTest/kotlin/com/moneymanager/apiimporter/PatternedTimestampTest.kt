package com.moneymanager.apiimporter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** Unit tests for [parsePatternedTimestamp] ([com.moneymanager.domain.model.apistrategy.TimestampFormat.PATTERN]). */
class PatternedTimestampTest {
    @Test
    fun `parses Binance's withdrawal-history date-time pattern as UTC`() {
        assertEquals(
            Instant.parse("2023-11-14T22:13:20Z"),
            parsePatternedTimestamp("2023-11-14 22:13:20", "yyyy-MM-dd HH:mm:ss"),
        )
    }

    @Test
    fun `epoch boundary and leap-year dates round-trip`() {
        assertEquals(Instant.fromEpochMilliseconds(0), parsePatternedTimestamp("1970-01-01 00:00:00", "yyyy-MM-dd HH:mm:ss"))
        assertEquals(
            Instant.parse("2024-02-29T12:00:00Z"),
            parsePatternedTimestamp("2024-02-29 12:00:00", "yyyy-MM-dd HH:mm:ss"),
        )
    }

    @Test
    fun `a value that does not match the pattern's shape returns null`() {
        assertNull(parsePatternedTimestamp("not-a-date", "yyyy-MM-dd HH:mm:ss"))
        assertNull(parsePatternedTimestamp("2023-11-14", "yyyy-MM-dd HH:mm:ss"))
        assertNull(parsePatternedTimestamp("2023-13-14 22:13:20", "yyyy-MM-dd HH:mm:ss"))
    }
}
