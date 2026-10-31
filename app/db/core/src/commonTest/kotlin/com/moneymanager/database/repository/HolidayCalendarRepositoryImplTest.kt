package com.moneymanager.database.repository

import com.moneymanager.domain.model.calendar.HolidayCalendar
import com.moneymanager.importengineapi.deleteHolidayCalendar
import com.moneymanager.importengineapi.upsertHolidayCalendar
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class HolidayCalendarRepositoryImplTest : DbTest() {
    private val budapest =
        HolidayCalendar(
            id = "HUBU",
            name = "Budapest",
            timeZone = "Europe/Budapest",
            countries = listOf("HU"),
            weekend = listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            holidays = listOf(LocalDate(2024, 3, 15), LocalDate(2024, 8, 19), LocalDate(2024, 8, 20)),
            workingDays = listOf(LocalDate(2024, 8, 3)),
        )

    private val dubai =
        HolidayCalendar(
            id = "AEDB",
            name = "Dubai",
            timeZone = "Asia/Dubai",
            countries = listOf("AE"),
            weekend = listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
        )

    private suspend fun calendars() = repositories.holidayCalendarRepository.getAll().first()

    @Test
    fun `a calendar reads back as it was written`() =
        runDbTest {
            repositories.importEngine.upsertHolidayCalendar(budapest)
            repositories.importEngine.upsertHolidayCalendar(dubai)

            assertEquals(listOf(budapest, dubai), calendars())
        }

    @Test
    fun `replacing a calendar replaces its countries and weekend and days`() =
        runDbTest {
            repositories.importEngine.upsertHolidayCalendar(budapest)
            val replaced =
                budapest.copy(
                    name = "Budapest (Hungary)",
                    countries = emptyList(),
                    weekend = listOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
                    holidays = listOf(LocalDate(2025, 3, 15)),
                    workingDays = emptyList(),
                )
            repositories.importEngine.upsertHolidayCalendar(replaced)

            assertEquals(listOf(replaced), calendars())
        }

    @Test
    fun `deleting a calendar leaves the others whole`() =
        runDbTest {
            repositories.importEngine.upsertHolidayCalendar(budapest)
            repositories.importEngine.upsertHolidayCalendar(dubai)
            repositories.importEngine.deleteHolidayCalendar(budapest.id)

            assertEquals(listOf(dubai), calendars())
            // The deleted calendar's rows went with it: re-creating it bare doesn't bring them back.
            repositories.importEngine.upsertHolidayCalendar(
                HolidayCalendar(id = "HUBU", name = "Budapest", timeZone = "Europe/Budapest", weekend = emptyList()),
            )
            assertEquals(
                HolidayCalendar(id = "HUBU", name = "Budapest", timeZone = "Europe/Budapest", weekend = emptyList()),
                calendars().single { it.id == "HUBU" },
            )
        }
}
