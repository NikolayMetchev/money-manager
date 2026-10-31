package com.moneymanager.database.repository

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.coroutines.asFlow
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.calendar.HolidayCalendar
import com.moneymanager.domain.repository.HolidayCalendarReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.coroutines.CoroutineContext

class HolidayCalendarReadRepositoryImpl(
    database: MoneyManagerDatabase,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : HolidayCalendarReadRepository {
    private val selectQueries = database.holidayCalendarSelectQueries

    // A calendar's rows are only ever written together with it, so a change to the calendar table is a change to any.
    // They're read in one transaction, so a calendar is never paired with another write's rows.
    override fun getAll(): Flow<List<HolidayCalendar>> =
        selectQueries
            .selectAll()
            .asFlow()
            .map {
                selectQueries.transactionWithResult {
                    val rows = selectQueries.selectAll().awaitAsList()
                    val countries = selectQueries.selectAllCountries().awaitAsList().groupBy({ it.calendar_code }, { it.country_code })
                    val weekends =
                        selectQueries
                            .selectAllWeekendDays()
                            .awaitAsList()
                            .groupBy({ it.calendar_code }, { DayOfWeek(it.iso_day_of_week.toInt()) })
                    val days = selectQueries.selectAllDays().awaitAsList().groupBy { it.calendar_code }
                    rows.map { row ->
                        val (working, holidays) = days[row.code].orEmpty().partition { it.working == 1L }
                        HolidayCalendar(
                            id = row.code,
                            name = row.name,
                            timeZone = row.time_zone,
                            countries = countries[row.code].orEmpty(),
                            weekend = weekends[row.code].orEmpty(),
                            holidays = holidays.map { LocalDate.fromEpochDays(it.epoch_day) },
                            workingDays = working.map { LocalDate.fromEpochDays(it.epoch_day) },
                        )
                    }
                }
            }.flowOn(coroutineContext)
}
