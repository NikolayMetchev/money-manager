@file:OptIn(kotlinx.datetime.format.FormatStringsInDatetimeFormats::class)

package com.moneymanager.csvimporter

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.format.byUnicodePattern

/**
 * Guesses the date/time format string for a CSV column from its sample values.
 *
 * Candidates are tried in priority order and the first one that parses **every** non-blank sample is
 * returned, so the UI can pre-fill the format field instead of making the user hand-write a
 * [byUnicodePattern]. Parsing goes through the very same `kotlinx.datetime` pattern engine the
 * importer uses ([CsvTransferMapper]), so a detected format is guaranteed to parse at import time.
 *
 * Ambiguous numeric layouts (e.g. `05/06/2026`) resolve to the higher-priority candidate; this app
 * is UK-centric, so day-first (`dd/MM/yyyy`) is preferred over month-first.
 */
object DateFormatDetector {
    /** Combined date+time patterns, most common first. */
    val dateTimeCandidates: List<String> =
        listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd'T'HH:mm",
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm",
            "MM/dd/yyyy HH:mm:ss",
            "MM/dd/yyyy HH:mm",
            "dd-MM-yyyy HH:mm:ss",
            "dd.MM.yyyy HH:mm:ss",
        )

    /** Date-only patterns, most common first. */
    val dateCandidates: List<String> =
        listOf(
            "yyyy-MM-dd",
            "dd/MM/yyyy",
            "MM/dd/yyyy",
            "dd-MM-yyyy",
            "dd.MM.yyyy",
            "yyyy/MM/dd",
            "d/M/yyyy",
            "M/d/yyyy",
        )

    /** Time-only patterns, most common first. */
    val timeCandidates: List<String> =
        listOf(
            "HH:mm:ss",
            "HH:mm",
            "H:mm:ss",
            "H:mm",
        )

    /** Detects a combined date+time format, or null if no candidate parses every sample. */
    fun detectDateTime(samples: List<String>): String? =
        firstMatching(dateTimeCandidates, samples) { pattern, value ->
            LocalDateTime.Format { byUnicodePattern(pattern) }.parse(value)
        }

    /** Detects a date-only format, or null if no candidate parses every sample. */
    fun detectDate(samples: List<String>): String? =
        firstMatching(dateCandidates, samples) { pattern, value ->
            LocalDate.Format { byUnicodePattern(pattern) }.parse(value)
        }

    /** Detects a time-only format, or null if no candidate parses every sample. */
    fun detectTime(samples: List<String>): String? =
        firstMatching(timeCandidates, samples) { pattern, value ->
            LocalTime.Format { byUnicodePattern(pattern) }.parse(value)
        }

    /** Whether [pattern] is a valid combined date+time format that parses [value] (after trimming). */
    fun parsesAsDateTime(
        pattern: String,
        value: String,
    ): Boolean = tryParse { LocalDateTime.Format { byUnicodePattern(pattern) }.parse(value.trim()) }

    /** Whether [pattern] is a valid date-only format that parses [value] (after trimming). */
    fun parsesAsDate(
        pattern: String,
        value: String,
    ): Boolean = tryParse { LocalDate.Format { byUnicodePattern(pattern) }.parse(value.trim()) }

    /** Whether [pattern] is a valid time-only format that parses [value] (after trimming). */
    fun parsesAsTime(
        pattern: String,
        value: String,
    ): Boolean = tryParse { LocalTime.Format { byUnicodePattern(pattern) }.parse(value.trim()) }

    private inline fun tryParse(block: () -> Unit): Boolean =
        try {
            block()
            true
        } catch (_: Exception) {
            false
        }

    private inline fun firstMatching(
        candidates: List<String>,
        samples: List<String>,
        parse: (pattern: String, value: String) -> Any,
    ): String? {
        val values = samples.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_SAMPLES)
        if (values.isEmpty()) return null
        return candidates.firstOrNull { pattern ->
            values.all { value ->
                try {
                    parse(pattern, value)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    private const val MAX_SAMPLES = 50
}
