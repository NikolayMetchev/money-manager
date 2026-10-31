package com.moneymanager.database.repository.write

import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.calendar.HolidayCalendar
import com.moneymanager.domain.repository.HolidayCalendarReadRepository
import com.moneymanager.domain.repository.write.HolidayCalendarWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.isoDayNumber
import kotlin.coroutines.CoroutineContext

class HolidayCalendarWriteRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
    reader: HolidayCalendarReadRepository,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : HolidayCalendarWriteRepository,
    HolidayCalendarReadRepository by reader {
    private val writeQueries = database.holidayCalendarWriteQueries

    override suspend fun upsert(calendar: HolidayCalendar): Unit =
        withContext(coroutineContext) {
            val code = calendar.id
            writeQueries.transaction {
                writeQueries.upsert(code = code, name = calendar.name, time_zone = calendar.timeZone)
                writeQueries.deleteCountries(code)
                calendar.countries.toSet().forEach { writeQueries.insertCountry(code, it) }
                writeQueries.deleteWeekendDays(code)
                calendar.weekend.toSet().forEach { writeQueries.insertWeekendDay(code, it.isoDayNumber.toLong()) }
                writeQueries.deleteDays(code)
                calendar.holidays.toSet().forEach { writeQueries.insertDay(code, it.toEpochDays(), 0) }
                // A date listed as both is a holiday: Strata's calendars never list one twice.
                (calendar.workingDays.toSet() - calendar.holidays.toSet()).forEach { writeQueries.insertDay(code, it.toEpochDays(), 1) }
            }
        }

    override suspend fun delete(code: String): Unit =
        withContext(coroutineContext) {
            writeQueries.deleteByCode(code)
        }
}
