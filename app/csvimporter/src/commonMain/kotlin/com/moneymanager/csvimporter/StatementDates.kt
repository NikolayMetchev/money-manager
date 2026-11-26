package com.moneymanager.csvimporter

import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleEvaluator
import com.moneymanager.domain.model.rules.ValueExpr
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Instant

internal class StatementDates(
    private val rules: RuleEvaluator,
) {
    private val dateFormats = DateFormats()

    /**
     * When the row's statement balance counts it ([RuleAction.Balance.at]), read as [mapping] reads the row's own date:
     * null when the cell is blank (a pending row), so the balance is the row's own time's.
     */
    fun parseBalanceAt(
        at: ValueExpr,
        mapping: DateTimeParsingMapping,
        values: RowValues,
        timezone: TimeZone,
    ): Instant? {
        val raw = rules.resolve(at) { values.record.text(it)?.trim() }.trim()
        if (raw.isEmpty()) return null
        return parseTimestamp(mapping, values, timezone, raw) ?: error("Failed to parse balance time: $raw")
    }

    fun parseTimestamp(
        mapping: DateTimeParsingMapping,
        values: RowValues,
        timezone: TimeZone,
        dateValue: String = values.required(mapping.dateColumnName),
    ): Instant? {
        val timeValue =
            mapping.timeColumnName?.let { values.required(it) }
                ?: mapping.defaultTime

        val dateTimeFormat = mapping.dateTimeFormat
        return try {
            if (dateTimeFormat != null) {
                // Single column holding a combined date+time value
                dateFormats.dateTime(dateValue, dateTimeFormat).toInstant(timezone)
            } else {
                val reference =
                    mapping.yearFrom?.let { yearFrom ->
                        { dateFormats.date(values.required(yearFrom.column), yearFrom.format) }
                    }
                val date = dateFormats.date(dateValue, mapping.dateFormat, reference)
                val time = if (timeValue.isBlank()) LocalTime(12, 0, 0) else dateFormats.time(timeValue, mapping.timeFormat ?: "HH:mm[:ss]")
                LocalDateTime(date, time).toInstant(timezone)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun parseTimezone(
        mapping: FieldMapping?,
        values: RowValues,
    ): TimeZone =
        when (mapping) {
            is HardCodedTimezoneMapping -> {
                TimeZone.of(mapping.timezoneId)
            }

            is TimezoneLookupMapping -> {
                val value = values.required(mapping.columnName).trim()
                val tzId =
                    mapping.aliases.entries
                        .firstOrNull { it.key.equals(value, ignoreCase = true) }
                        ?.value ?: value
                if (tzId.isNotBlank()) TimeZone.of(tzId) else TimeZone.currentSystemDefault()
            }

            else -> {
                TimeZone.currentSystemDefault()
            }
        }
}
