package com.moneymanager.apiimporter

import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.PaginationMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

class IncrementalDateWindowsTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private val pagination =
        ApiPaginationConfig(
            mode = PaginationMode.DATE_WINDOW,
            windowDays = 7,
            lookbackDays = 365,
            incrementalOverlapDays = 5,
        )

    @Test
    fun `no watermark sweeps the whole configured lookback`() {
        val windows = dateWindows(pagination, now)

        val lookbackStart = now.toEpochMilliseconds() - 365 * MILLIS_PER_DAY
        // Anchored to a window boundary, so the first window starts at or just before the lookback.
        assertTrue(windows.first().start.toEpochMilliseconds() <= lookbackStart)
        assertTrue(windows.first().start.toEpochMilliseconds() > lookbackStart - 7 * MILLIS_PER_DAY)
        assertEquals(now, windows.last().end)
    }

    @Test
    fun `a watermark starts the sweep at the watermark less the overlap`() {
        val since = now - kotlin.time.Duration.parse("30d")

        val windows = dateWindows(pagination, now, since)

        val expectedStart = since.toEpochMilliseconds() - 5 * MILLIS_PER_DAY
        val firstStart = windows.first().start.toEpochMilliseconds()
        assertTrue(firstStart <= expectedStart, "first window must cover the overlap")
        assertTrue(firstStart > expectedStart - 7 * MILLIS_PER_DAY, "and must not reach further back than one window")
        assertEquals(now, windows.last().end)
        // 35 days of history in 7-day windows is far fewer requests than a full 365-day sweep.
        assertTrue(windows.size < dateWindows(pagination, now).size)
    }

    @Test
    fun `a watermark older than the lookback cannot widen the sweep`() {
        val since = now - kotlin.time.Duration.parse("3650d")

        assertEquals(dateWindows(pagination, now).map { it.start }, dateWindows(pagination, now, since).map { it.start })
    }

    @Test
    fun `window boundaries stay anchored so earlier windows keep stable urls`() {
        val full = dateWindows(pagination, now)
        val incremental = dateWindows(pagination, now, now - kotlin.time.Duration.parse("30d"))

        // Every incremental window must be one of the full sweep's windows, byte-for-byte, otherwise a
        // window already stored under its old URL would be re-fetched under a new one.
        assertTrue(incremental.dropLast(1).all { it in full })
    }

    @Test
    fun `every window's span stays strictly under the configured window length`() {
        val windowMillis = 7 * MILLIS_PER_DAY
        // A now that lands exactly on a window boundary is the case that used to produce a
        // full-length (provider-rejecting) final span.
        val alignedNow = Instant.fromEpochMilliseconds((now.toEpochMilliseconds() / windowMillis) * windowMillis)

        listOf(now, alignedNow).forEach { end ->
            dateWindows(pagination, end).forEach { window ->
                val span = window.end.toEpochMilliseconds() - window.start.toEpochMilliseconds()
                assertTrue(span < windowMillis, "window $window spans $span, not under $windowMillis")
            }
        }
    }

    @Test
    fun `a negative overlap is clamped so it can never skip past the watermark`() {
        val since = now - kotlin.time.Duration.parse("30d")
        val negative = pagination.copy(incrementalOverlapDays = -3)

        // Without the clamp this would start three days AFTER the watermark, dropping those records.
        assertEquals(since.toEpochMilliseconds(), incrementalStartMillis(since, negative))
        assertTrue(dateWindows(negative, now, since).first().start <= since)
    }

    @Test
    fun `zero overlap starts exactly at the watermark`() {
        val since = now - kotlin.time.Duration.parse("30d")
        val noOverlap = pagination.copy(incrementalOverlapDays = 0)

        assertEquals(
            since.toEpochMilliseconds(),
            incrementalStartMillis(since, noOverlap),
        )
    }
}
