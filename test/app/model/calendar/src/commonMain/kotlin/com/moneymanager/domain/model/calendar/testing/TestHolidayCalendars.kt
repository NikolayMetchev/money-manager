package com.moneymanager.domain.model.calendar.testing

import com.moneymanager.domain.model.calendar.HolidayCalendar
import kotlinx.datetime.LocalDate

/**
 * Locations for tests. The app ships none (the strategy catalog publishes Strata's), so a test that counts business
 * days installs or builds one of these.
 */
object TestHolidayCalendars {
    /** gov.uk's bank-holidays.json, England and Wales, 2019–2027. */
    val ENGLAND_AND_WALES_BANK_HOLIDAYS: List<LocalDate> =
        (
            "2019-01-01 2019-04-19 2019-04-22 2019-05-06 2019-05-27 2019-08-26 2019-12-25 2019-12-26 2020-01-01 " +
                "2020-04-10 2020-04-13 2020-05-08 2020-05-25 2020-08-31 2020-12-25 2020-12-28 2021-01-01 2021-04-02 " +
                "2021-04-05 2021-05-03 2021-05-31 2021-08-30 2021-12-27 2021-12-28 2022-01-03 2022-04-15 2022-04-18 " +
                "2022-05-02 2022-06-02 2022-06-03 2022-08-29 2022-09-19 2022-12-26 2022-12-27 2023-01-02 2023-04-07 " +
                "2023-04-10 2023-05-01 2023-05-08 2023-05-29 2023-08-28 2023-12-25 2023-12-26 2024-01-01 2024-03-29 " +
                "2024-04-01 2024-05-06 2024-05-27 2024-08-26 2024-12-25 2024-12-26 2025-01-01 2025-04-18 2025-04-21 " +
                "2025-05-05 2025-05-26 2025-08-25 2025-12-25 2025-12-26 2026-01-01 2026-04-03 2026-04-06 2026-05-04 " +
                "2026-05-25 2026-08-31 2026-12-25 2026-12-28 2027-01-01 2027-03-26 2027-03-29 2027-05-03 2027-05-31 " +
                "2027-08-30 2027-12-27 2027-12-28"
        ).split(' ').map(LocalDate::parse)

    /** London as Strata publishes it, for 2019–2027. */
    val LONDON: HolidayCalendar =
        HolidayCalendar(
            id = "GBLO",
            name = "London",
            timeZone = "Europe/London",
            countries = listOf("GB"),
            holidays = ENGLAND_AND_WALES_BANK_HOLIDAYS,
        )

    /** New York's 2024 holidays (NYSE and the Fed agree on these). */
    val NEW_YORK_2024: HolidayCalendar =
        HolidayCalendar(
            id = "USNY",
            name = "New York",
            timeZone = "America/New_York",
            countries = listOf("US"),
            holidays =
                listOf(
                    LocalDate(2024, 1, 1),
                    LocalDate(2024, 1, 15),
                    LocalDate(2024, 2, 19),
                    LocalDate(2024, 5, 27),
                    LocalDate(2024, 6, 19),
                    LocalDate(2024, 7, 4),
                    LocalDate(2024, 9, 2),
                    LocalDate(2024, 10, 14),
                    LocalDate(2024, 11, 11),
                    LocalDate(2024, 11, 28),
                    LocalDate(2024, 12, 25),
                ),
        )
}
