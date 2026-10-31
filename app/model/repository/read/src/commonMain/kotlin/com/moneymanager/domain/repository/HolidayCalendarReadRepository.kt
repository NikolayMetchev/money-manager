package com.moneymanager.domain.repository

import com.moneymanager.domain.model.calendar.HolidayCalendar
import kotlinx.coroutines.flow.Flow

interface HolidayCalendarReadRepository {
    /** Every location's calendar, ordered by name. */
    fun getAll(): Flow<List<HolidayCalendar>>
}
