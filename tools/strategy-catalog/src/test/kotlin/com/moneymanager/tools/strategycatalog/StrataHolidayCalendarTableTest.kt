package com.moneymanager.tools.strategycatalog

import com.moneymanager.domain.model.calendar.StrataHolidayCalendars
import kotlin.test.Test
import kotlin.test.assertEquals

class StrataHolidayCalendarTableTest {
    @Test
    fun `the bundled calendars match Strata`() {
        assertEquals(
            strataHolidayCalendars(),
            StrataHolidayCalendars.all,
            "app/model/calendar's StrataHolidayCalendars.kt is stale: run bazel run //tools/strategy-catalog:generate_bundled_calendars",
        )
    }

    @Test
    fun `every Strata calendar is bundled`() {
        assertEquals(strataCalendarIds(), STRATA_LOCATIONS.map { it.id }.toSet())
    }
}
