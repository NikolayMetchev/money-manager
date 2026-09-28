package com.moneymanager.humanreadable

import kotlin.math.absoluteValue
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * English-only file size, duration and relative-time formatting.
 *
 * Replaces the `nl.jacobras:Human-Readable` library, whose localisation layer (libres) dragged the
 * whole of ICU4J (~11 MB compressed) into the desktop build for three functions. The output matches
 * that library's English strings, so the UI text is unchanged.
 */
object HumanReadable {
    /** "3.5 MB" for 3_670_016 bytes. Base 1024, [decimals] fraction digits (none for plain bytes). */
    fun fileSize(
        bytes: Long,
        decimals: Int = 1,
    ): String =
        when {
            bytes < KIB -> "$bytes B"
            bytes < MIB -> "${(bytes / KIB.toDouble()).formatNumber(decimals)} kB"
            bytes < GIB -> "${(bytes / MIB.toDouble()).formatNumber(decimals)} MB"
            bytes < TIB -> "${(bytes / GIB.toDouble()).formatNumber(decimals)} GB"
            else -> "${(bytes / TIB.toDouble()).formatNumber(decimals)} TB"
        }

    /** The largest sensible unit, rounded: "45 seconds", "3 hours", "2 weeks", "1 year". */
    fun duration(duration: Duration): String {
        val seconds = duration.inWholeSeconds
        val days = duration.inWholeDays
        val months = (days / DAYS_PER_MONTH).roundToInt()
        val years = days / DAYS_PER_YEAR
        return when {
            seconds < SECONDS_PER_MINUTE -> unit(seconds, "second")
            seconds < SECONDS_PER_HOUR -> unit(duration.inWholeMinutes, "minute")
            days < 1 -> unit(duration.inWholeHours, "hour")
            days < DAYS_PER_WEEK -> unit(days, "day")
            days < DAYS_PER_MONTH_FLOOR -> unit((days / DAYS_PER_WEEK.toFloat()).roundToInt().toLong(), "week")
            months < MONTHS_PER_YEAR || years == 0L -> unit(months.toLong(), "month")
            else -> unit(years, "year")
        }
    }

    /** "3 days ago", "in 2 hours", or "now" within a second of [baseInstant]. */
    fun timeAgo(
        instant: Instant,
        baseInstant: Instant = Clock.System.now(),
    ): String {
        val diff = baseInstant - instant
        val secondsAgo = diff.inWholeSeconds
        return when {
            secondsAgo < 0 -> "in ${duration(diff.absoluteValue)}"
            secondsAgo <= 1 -> "now"
            else -> "${duration(diff)} ago"
        }
    }

    private fun unit(
        count: Long,
        name: String,
    ): String = if (count == 1L) "1 $name" else "$count ${name}s"

    private fun Double.formatNumber(decimals: Int): String {
        val scaled =
            (this * 10.0.pow(decimals))
                .roundToLong()
                .absoluteValue
                .toString()
                .padStart(decimals + 1, '0')
        val integerDigits = scaled.dropLast(decimals)
        val grouped =
            integerDigits
                .reversed()
                .chunked(DIGITS_PER_GROUP)
                .joinToString(",")
                .reversed()
        val sign = if (this < 0) "-" else ""
        return if (decimals > 0) "$sign$grouped.${scaled.takeLast(decimals)}" else "$sign$grouped"
    }

    private const val KIB = 1_024L
    private const val MIB = KIB * KIB
    private const val GIB = MIB * KIB
    private const val TIB = GIB * KIB
    private const val DIGITS_PER_GROUP = 3
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L
    private const val DAYS_PER_WEEK = 7L
    private const val DAYS_PER_MONTH_FLOOR = 30L
    private const val DAYS_PER_MONTH = 30.5f
    private const val MONTHS_PER_YEAR = 12
    private const val DAYS_PER_YEAR = 365L
}
