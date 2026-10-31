package com.moneymanager.domain.model.calendar

import com.moneymanager.domain.model.calendar.testing.TestHolidayCalendars
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HolidayCalendarTest {
    private val london = TestHolidayCalendars.LONDON

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
        val newYork = TestHolidayCalendars.NEW_YORK_2024
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
    }

    @Test
    fun `a locale's country suggests a location`() {
        val installed = listOf(TestHolidayCalendars.NEW_YORK_2024, TestHolidayCalendars.LONDON)
        assertEquals("GBLO", installed.forCountry("gb")?.id)
        assertEquals("USNY", installed.forCountry("US")?.id)
        assertNull(installed.forCountry("AQ"))
        assertNull(installed.forCountry(null))
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
