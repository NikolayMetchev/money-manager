package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.calendar.HolidayCalendar
import com.moneymanager.domain.repository.HolidayCalendarReadRepository

interface HolidayCalendarWriteRepository : HolidayCalendarReadRepository {
    /** Creates [calendar], or replaces the calendar with its code ([HolidayCalendar.id]). */
    suspend fun upsert(calendar: HolidayCalendar)

    suspend fun delete(code: String)
}
