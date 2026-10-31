package com.moneymanager.domain.model.calendar

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HolidayCalendarTest {
    private val london = StrataHolidayCalendars.all.single { it.id == "GBLO" }

    // gov.uk's bank-holidays.json, England and Wales, 2019–2027.
    private val govUkEnglandAndWales =
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

    @Test
    fun `London's bundled holidays are gov_uk's England and Wales bank holidays`() {
        val bundled = london.holidays.filter { it.year in 2019..2027 }
        assertEquals(govUkEnglandAndWales, bundled)
        assertEquals(HolidayCalendar.DEFAULT_WEEKEND, london.weekend)
    }

    @Test
    fun `Easter 2021 has no London business day between Thursday and Easter Monday`() {
        assertEquals(0, london.businessDaysBetween(LocalDate(2021, 4, 1), LocalDate(2021, 4, 5)))
        assertEquals(1, london.businessDaysBetween(LocalDate(2021, 4, 1), LocalDate(2021, 4, 6)))
        // Four calendar days, inside a 3-business-day window.
        assertTrue(london.withinBusinessDays(LocalDate(2021, 4, 1), LocalDate(2021, 4, 5), 3))
    }

    @Test
    fun `a week of ordinary days is outside a 3-business-day window`() {
        // Monday to the next Monday: 5 business days.
        assertEquals(5, london.businessDaysBetween(LocalDate(2021, 6, 7), LocalDate(2021, 6, 14)))
        assertFalse(london.withinBusinessDays(LocalDate(2021, 6, 7), LocalDate(2021, 6, 14), 3))
        assertTrue(london.withinBusinessDays(LocalDate(2021, 6, 7), LocalDate(2021, 6, 10), 3))
    }

    @Test
    fun `reach agrees with businessDaysBetween both ways`() {
        val thursday = LocalDate(2021, 4, 1)
        // Forward, the weekend and holidays after the third business day still count as within it.
        assertEquals(LocalDate(2021, 4, 8), london.reach(thursday, 3))
        assertEquals(LocalDate(2021, 4, 5), london.reach(LocalDate(2021, 3, 29), 3))
        // Backward from the Tuesday after Easter: Tue 6, Thu 1, Wed 31 (Good Friday and Easter Monday are skipped).
        assertEquals(LocalDate(2021, 3, 30), london.reach(LocalDate(2021, 4, 6), -3))
        for (days in 0..5) {
            assertEquals(days, london.businessDaysBetween(thursday, london.reach(thursday, days)))
            assertEquals(days, london.businessDaysBetween(london.reach(thursday, -days), thursday))
        }
    }

    @Test
    fun `a combined calendar is closed whenever any of its calendars is`() {
        val newYork = StrataHolidayCalendars.all.single { it.id == "USNY" }
        val both = CombinedCalendar(listOf(london, newYork))
        // Independence Day, a London business day.
        assertTrue(london.isBusinessDay(LocalDate(2024, 7, 4)))
        assertFalse(both.isBusinessDay(LocalDate(2024, 7, 4)))
        // Easter Monday, a New York business day.
        assertTrue(newYork.isBusinessDay(LocalDate(2024, 4, 1)))
        assertFalse(both.isBusinessDay(LocalDate(2024, 4, 1)))
        assertEquals(london.zone, both.zone)
    }

    @Test
    fun `a working day on a weekend is a business day`() {
        val calendar = HolidayCalendar("TEST", "Test", "UTC", workingDays = listOf(LocalDate(2024, 6, 1)))
        assertTrue(calendar.isBusinessDay(LocalDate(2024, 6, 1)))
        assertFalse(calendar.isBusinessDay(LocalDate(2024, 6, 2)))
        assertTrue(StrataHolidayCalendars.all.any { it.workingDays.isNotEmpty() })
    }

    @Test
    fun `a locale's country suggests a location`() {
        assertEquals("GBLO", StrataHolidayCalendars.all.forCountry("gb")?.id)
        assertEquals("EUTA", StrataHolidayCalendars.all.forCountry("DE")?.id)
        assertNull(StrataHolidayCalendars.all.forCountry("AQ"))
        assertNull(StrataHolidayCalendars.all.forCountry(null))
    }

    @Test
    fun `every bundled calendar has a valid zone and no country served twice`() {
        for (calendar in StrataHolidayCalendars.all) assertNotNull(calendar.zone)
        val countries = StrataHolidayCalendars.all.flatMap { it.countries }
        assertEquals(countries.size, countries.toSet().size)
    }

    @Test
    fun `a calendar's JSON lists its dates in order whatever order they were given`() {
        val calendar =
            HolidayCalendar(
                id = "TEST",
                name = "Test",
                timeZone = "UTC",
                holidays = listOf(LocalDate(2024, 12, 25), LocalDate(2024, 1, 1)),
            )
        val json = Json.encodeToString(HolidayCalendar.serializer(), calendar)
        assertTrue(json.indexOf("2024-01-01") < json.indexOf("2024-12-25"), json)
        assertEquals(calendar.copy(holidays = calendar.holidays.sorted()), Json.decodeFromString(HolidayCalendar.serializer(), json))
    }
}
