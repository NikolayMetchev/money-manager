package com.moneymanager.database.json

import com.moneymanager.domain.model.calendar.HolidayCalendar
import kotlinx.serialization.json.Json

/** Codec for `holiday_calendar.calendar_json`, and the catalog's holiday-calendar artifacts. */
object HolidayCalendarJsonCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(calendar: HolidayCalendar): String = json.encodeToString(HolidayCalendar.serializer(), calendar)

    fun decode(jsonString: String): HolidayCalendar = json.decodeFromString(HolidayCalendar.serializer(), jsonString)
}
