package com.moneymanager.database.json

import com.moneymanager.domain.model.calendar.HolidayCalendarExport
import kotlinx.serialization.json.Json

/** Codec for the holiday-calendar artifacts published in the strategy catalog and synced with the library. */
object HolidayCalendarExportCodec {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encode(export: HolidayCalendarExport): String = json.encodeToString(export)

    /** Unknown keys are ignored for forward compatibility. */
    fun decode(jsonString: String): HolidayCalendarExport = json.decodeFromString(jsonString)
}
