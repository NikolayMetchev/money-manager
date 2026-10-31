package com.moneymanager.domain.model.calendar

/** The installed location a device in [country] (ISO 3166 alpha-2) would most likely bank in, if any. */
fun List<HolidayCalendar>.forCountry(country: String?): HolidayCalendar? =
    country?.uppercase()?.let { code -> firstOrNull { code in it.countries } }
