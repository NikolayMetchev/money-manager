package com.moneymanager.database.repository

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.coroutines.asFlow
import com.moneymanager.database.sql.holidayCalendar.Holiday_calendar
import com.moneymanager.database.sql.holidayCalendar.Holiday_calendar_country
import com.moneymanager.database.sql.holidayCalendar.Holiday_calendar_day
import com.moneymanager.database.sql.holidayCalendar.Holiday_calendar_weekend_day
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.calendar.HolidayCalendar
import com.moneymanager.domain.repository.HolidayCalendarReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
                    calendars(
                        rows = selectQueries.selectAll().awaitAsList(),
                        countries = selectQueries.selectAllCountries().awaitAsList(),
                        weekendDays = selectQueries.selectAllWeekendDays().awaitAsList(),
                        days = selectQueries.selectAllDays().awaitAsList(),
                    )
                }
            }.flowOn(coroutineContext)

    override suspend fun getByCodes(codes: Collection<String>): List<HolidayCalendar> {
        if (codes.isEmpty()) return emptyList()
        val distinct = codes.distinct()
        return withContext(coroutineContext) {
            selectQueries.transactionWithResult {
                calendars(
                    rows = selectQueries.selectByCodes(distinct).awaitAsList(),
                    countries = selectQueries.selectCountriesByCodes(distinct).awaitAsList(),
                    weekendDays = selectQueries.selectWeekendDaysByCodes(distinct).awaitAsList(),
                    days = selectQueries.selectDaysByCodes(distinct).awaitAsList(),
                )
            }
        }
    }

    private fun calendars(
        rows: List<Holiday_calendar>,
        countries: List<Holiday_calendar_country>,
        weekendDays: List<Holiday_calendar_weekend_day>,
        days: List<Holiday_calendar_day>,
    ): List<HolidayCalendar> {
        val countriesByCode = countries.groupBy({ it.calendar_code }, { it.country_code })
        val weekendsByCode = weekendDays.groupBy({ it.calendar_code }, { DayOfWeek(it.iso_day_of_week.toInt()) })
        val daysByCode = days.groupBy { it.calendar_code }
        return rows.map { row ->
            val (working, holidays) = daysByCode[row.code].orEmpty().partition { it.working == 1L }
            HolidayCalendar(
                id = row.code,
                name = row.name,
                timeZone = row.time_zone,
                countries = countriesByCode[row.code].orEmpty(),
                weekend = weekendsByCode[row.code].orEmpty(),
                holidays = holidays.map { LocalDate.fromEpochDays(it.epoch_day) },
                workingDays = working.map { LocalDate.fromEpochDays(it.epoch_day) },
            )
        }
    }
}
