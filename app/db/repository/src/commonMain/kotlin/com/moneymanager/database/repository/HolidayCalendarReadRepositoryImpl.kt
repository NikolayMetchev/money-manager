package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.moneymanager.database.json.HolidayCalendarJsonCodec
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.calendar.HolidayCalendar
import com.moneymanager.domain.repository.HolidayCalendarReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext

class HolidayCalendarReadRepositoryImpl(
    database: MoneyManagerDatabase,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : HolidayCalendarReadRepository {
    private val selectQueries = database.holidayCalendarSelectQueries

    override fun getAll(): Flow<List<HolidayCalendar>> =
        selectQueries
            .selectAll()
            .asFlow()
            .mapToList(coroutineContext)
            .map { rows -> rows.map { HolidayCalendarJsonCodec.decode(it.calendar_json) } }
}
