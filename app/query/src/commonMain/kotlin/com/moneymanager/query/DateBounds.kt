package com.moneymanager.query

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlin.time.Instant

/**
 * One end of a date range as a person types it: a date (`YYYY-MM-DD`, in [zone]) or an ISO-8601 instant. A date
 * [through] the end of the day is the start of the next one, so an upper bound given as a date includes that day.
 * The CLI's `--from`/`--to` and the TUI's range and jump prompts all read dates this way.
 *
 * @throws IllegalArgumentException if [value] is neither
 */
fun parseDateBound(
    value: String,
    zone: TimeZone,
    through: Boolean,
): Instant {
    runCatching { LocalDate.parse(value.trim()) }.getOrNull()?.let { date ->
        return (if (through) date.plus(DatePeriod(days = 1)) else date).atStartOfDayIn(zone)
    }
    return runCatching { Instant.parse(value.trim()) }.getOrElse {
        throw IllegalArgumentException("'$value' isn't a date (YYYY-MM-DD) or an ISO-8601 instant")
    }
}
