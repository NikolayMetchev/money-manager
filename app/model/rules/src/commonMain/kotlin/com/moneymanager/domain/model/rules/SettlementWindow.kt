package com.moneymanager.domain.model.rules

import com.moneymanager.domain.model.calendar.BusinessDayCalendar
import com.moneymanager.domain.model.calendar.dateOf
import com.moneymanager.domain.model.calendar.reach
import com.moneymanager.domain.model.calendar.withinBusinessDays
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * How far apart two sources' records of one movement may be and still be reconciled: every reconcile window, CSV and
 * API alike. [Elapsed] is clock time, for sources that stamp a movement to the minute; [BusinessDays] counts the days
 * the movement's accounts settle on, so a payment made before a bank holiday weekend still meets the statement that
 * books it after. Which days those are comes from the accounts' locations (see `HolidayCalendar`), never the strategy.
 */
@Serializable
sealed interface SettlementWindow {
    @Serializable
    @SerialName("elapsed")
    data class Elapsed(
        val seconds: Long,
    ) : SettlementWindow {
        val duration: Duration get() = seconds.seconds
    }

    /** At most [days] business days between the two records' dates, read in the calendar's zone. */
    @Serializable
    @SerialName("businessDays")
    data class BusinessDays(
        val days: Int,
    ) : SettlementWindow
}

/** Whether [a] and [b] are within this window of each other, either order, on [calendar]'s days. */
fun SettlementWindow.contains(
    a: Instant,
    b: Instant,
    calendar: BusinessDayCalendar,
): Boolean =
    when (this) {
        is SettlementWindow.Elapsed -> {
            (a - b).absoluteValue <= duration
        }

        is SettlementWindow.BusinessDays -> {
            val (earlier, later) = if (a <= b) a to b else b to a
            calendar.withinBusinessDays(calendar.dateOf(earlier), calendar.dateOf(later), days)
        }
    }

/**
 * Whether two records meet: one stamped at [start] (or anywhere in `[start, until]` when its time is only a range) and
 * the other at [otherStart] (or in `[otherStart, otherUntil]`). Ranges that overlap meet; otherwise their nearest ends
 * must be within the window.
 */
fun SettlementWindow.meets(
    start: Instant,
    until: Instant?,
    otherStart: Instant,
    otherUntil: Instant?,
    calendar: BusinessDayCalendar,
): Boolean {
    val end = maxOf(start, until ?: start)
    val otherEnd = maxOf(otherStart, otherUntil ?: otherStart)
    return when {
        end < otherStart -> contains(end, otherStart, calendar)
        otherEnd < start -> contains(otherEnd, start, calendar)
        else -> true
    }
}

/**
 * Every instant a record within this window of `[from, until]` could carry, for loading candidates. Business days are
 * walked on [calendar] (the strictest calendar a match could use, so its reach is the furthest) and padded by a day
 * either side, since a pair's own calendar may read dates in another zone.
 */
fun SettlementWindow.widen(
    from: Instant,
    until: Instant,
    calendar: BusinessDayCalendar,
): ClosedRange<Instant> =
    when (this) {
        is SettlementWindow.Elapsed -> {
            (from - duration)..(until + duration)
        }

        is SettlementWindow.BusinessDays -> {
            val zone = calendar.zone
            val first = calendar.reach(calendar.dateOf(from), -days).plus(DatePeriod(days = -1))
            val last = calendar.reach(calendar.dateOf(until), days).plus(DatePeriod(days = 2))
            first.atStartOfDayIn(zone)..last.atStartOfDayIn(zone)
        }
    }
