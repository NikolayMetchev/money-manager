package com.moneymanager.domain.model.calendar

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/** Builds one of [StrataHolidayCalendars]' calendars from its space-separated generated fields. */
internal fun bundledCalendar(
    id: String,
    name: String,
    timeZone: String,
    countries: String,
    weekend: String,
    holidays: String,
    workingDays: String,
): HolidayCalendar =
    HolidayCalendar(
        id = id,
        name = name,
        timeZone = timeZone,
        countries = countries.words(),
        weekend = weekend.words().map(DayOfWeek::valueOf),
        holidays = holidays.words().map(LocalDate::parse),
        workingDays = workingDays.words().map(LocalDate::parse),
    )

private fun String.words(): List<String> = split(' ').filter { it.isNotEmpty() }

/** The bundled location a device in [country] (ISO 3166 alpha-2) would most likely bank in, if any. */
fun List<HolidayCalendar>.forCountry(country: String?): HolidayCalendar? =
    country?.uppercase()?.let { code -> firstOrNull { code in it.countries } }
