package com.moneymanager.tools.strategycatalog

import com.moneymanager.domain.model.calendar.HolidayCalendar
import com.moneymanager.domain.model.calendar.testing.TestHolidayCalendars
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StrataLocationTest {
    private val calendars = strataHolidayCalendars()

    @Test
    fun `every Strata calendar is published`() {
        assertEquals(strataCalendarIds(), STRATA_LOCATIONS.map { it.id }.toSet())
    }

    @Test
    fun `London's holidays are gov_uk's England and Wales bank holidays`() {
        val london = calendars.single { it.id == "GBLO" }
        assertEquals(TestHolidayCalendars.ENGLAND_AND_WALES_BANK_HOLIDAYS, london.holidays.filter { it.year in 2019..2027 })
        assertEquals(HolidayCalendar.DEFAULT_WEEKEND, london.weekend)
    }

    @Test
    fun `every location has a valid zone and no country is served twice`() {
        for (calendar in calendars) assertNotNull(calendar.zone)
        val countries = calendars.flatMap { it.countries }
        assertEquals(countries.size, countries.toSet().size)
        // Budapest's working Saturdays.
        assertTrue(calendars.any { it.workingDays.isNotEmpty() })
    }
}
