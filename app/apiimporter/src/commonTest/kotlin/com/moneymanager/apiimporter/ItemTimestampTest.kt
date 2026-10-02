package com.moneymanager.apiimporter

import com.moneymanager.domain.model.apistrategy.TimestampFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ItemTimestampTest {
    private val windowStart = Instant.fromEpochMilliseconds(1_640_736_000_000L)

    @Test
    fun `a real timestamp is used as is`() {
        assertEquals(
            Instant.fromEpochMilliseconds(1_699_867_096_000L),
            itemTimestamp("1699867096000", TimestampFormat.EPOCH_MS, null, windowStart),
        )
    }

    @Test
    fun `an epoch-zero placeholder falls back to the start of the fetch window`() {
        assertEquals(windowStart, itemTimestamp("0", TimestampFormat.EPOCH_MS, null, windowStart))
    }

    @Test
    fun `without a window an epoch-zero timestamp is kept and a missing one still skips the row`() {
        assertEquals(Instant.fromEpochMilliseconds(0), itemTimestamp("0", TimestampFormat.EPOCH_MS, null, null))
        assertNull(itemTimestamp(null, TimestampFormat.EPOCH_MS, null, windowStart))
    }

    @Test
    fun `the window start is read from an exchange marker url`() {
        val url =
            "https://api.bybit.com/v5/asset/deposit/query-record" +
                "?ep=v5/asset/deposit/query-record&ws=1640736000000&we=1643327999999&pg=0"
        assertEquals(windowStart, windowStartFromMarker(url))
        assertNull(windowStartFromMarker("https://api.bybit.com/v5/asset/exchange/query-convert-history?ep=x&pg=1"))
    }
}
