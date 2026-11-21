package com.moneymanager.domain.repository

import com.moneymanager.domain.model.calendar.HolidayCalendar
import kotlinx.coroutines.flow.Flow

interface HolidayCalendarReadRepository {
    /** Every location's calendar, ordered by name. */
    fun getAll(): Flow<List<HolidayCalendar>>

    /** The calendars of [codes] that exist, ordered by name: a code with no calendar is left out. */
    suspend fun getByCodes(codes: Collection<String>): List<HolidayCalendar>
}
