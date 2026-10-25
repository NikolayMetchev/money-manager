@file:OptIn(FormatStringsInDatetimeFormats::class)

package com.moneymanager.csvimporter

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.format.FormatStringsInDatetimeFormats
import kotlinx.datetime.format.byUnicodePattern

/**
 * Parses the dates a strategy's Unicode patterns describe, with the formats compiled once (building a
 * kotlinx-datetime format compiles a parser, which costs far more than the parse). On top of kotlinx-datetime's
 * patterns: `MMM`/`MMMM` read English month names, and a date pattern without a year reads the date in the
 * year that puts it on or before a reference date.
 */
internal class DateFormats {
    private val dateTimeFormats = HashMap<String, DateTimeFormat<LocalDateTime>>()
    private val dateFormats = HashMap<String, DateTimeFormat<LocalDate>>()
    private val timeFormats = HashMap<String, DateTimeFormat<LocalTime>>()

    fun dateTime(
        value: String,
        pattern: String,
    ): LocalDateTime {
        val (text, numeric) = withMonthNumbers(value, pattern)
        return dateTimeFormats.getOrPut(numeric) { LocalDateTime.Format { byUnicodePattern(numeric) } }.parse(text)
    }

    fun time(
        value: String,
        pattern: String,
    ): LocalTime = timeFormats.getOrPut(pattern) { LocalTime.Format { byUnicodePattern(pattern) } }.parse(value.trim())

    /**
     * [value] read with [pattern]. A pattern without a year needs [reference]: the date is read in its year, or
     * the year before when that would put it after [reference] (a December row on a January statement).
     */
    fun date(
        value: String,
        pattern: String,
        reference: (() -> LocalDate)? = null,
    ): LocalDate {
        val (text, numeric) = withMonthNumbers(value, pattern)
        if (YEAR_FIELD.containsMatchIn(numeric)) return format(numeric).parse(text)
        val referenceDate = requireNotNull(reference?.invoke()) { "Date format '$pattern' has no year and nothing to take one from" }
        val withYear = format("$numeric yyyy")
        val yearBefore = { withYear.parse("$text ${referenceDate.year - 1}") }
        // 29 February in a year that has none: only the year before can be meant.
        val sameYear = runCatching { withYear.parse("$text ${referenceDate.year}") }.getOrElse { return yearBefore() }
        return if (sameYear > referenceDate) yearBefore() else sameYear
    }

    private fun format(pattern: String): DateTimeFormat<LocalDate> =
        dateFormats.getOrPut(pattern) {
            LocalDate.Format { byUnicodePattern(pattern) }
        }

    private companion object {
        // y (year of era) and u (year) are the year fields; quoted literals aside, no other letter stands for one.
        val YEAR_FIELD = Regex("[yu]")
        val MONTH_NAME_FIELD = Regex("M{3,4}")
        val WHITESPACE = Regex("\\s+")
        val MONTH_NAMES =
            Regex(
                "\\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|" +
                    "sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\b",
                RegexOption.IGNORE_CASE,
            )

        /**
         * kotlinx-datetime's patterns have no month names, so a pattern with `MMM`/`MMMM` reads the month as a
         * number instead, the value's English month name replaced by that number. Runs of whitespace collapse
         * too, as the pattern's own spacing is single. Other patterns and values are left as they are.
         */
        fun withMonthNumbers(
            value: String,
            pattern: String,
        ): Pair<String, String> {
            if (!MONTH_NAME_FIELD.containsMatchIn(pattern)) return value.trim() to pattern
            val text =
                MONTH_NAMES.replace(value.trim().replace(WHITESPACE, " ")) { match ->
                    val month = MONTH_PREFIXES.indexOf(match.value.take(3).lowercase()) + 1
                    month.toString().padStart(2, '0')
                }
            return text to MONTH_NAME_FIELD.replace(pattern, "MM")
        }

        val MONTH_PREFIXES = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    }
}
