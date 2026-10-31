package com.moneymanager.database.repository.write

import com.moneymanager.database.json.HolidayCalendarJsonCodec
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.calendar.HolidayCalendar
import com.moneymanager.domain.repository.HolidayCalendarReadRepository
import com.moneymanager.domain.repository.write.HolidayCalendarWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            writeQueries.upsert(code = calendar.id, name = calendar.name, calendar_json = HolidayCalendarJsonCodec.encode(calendar))
        }

    override suspend fun delete(code: String): Unit =
        withContext(coroutineContext) {
            writeQueries.deleteByCode(code)
        }
}
