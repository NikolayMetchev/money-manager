package com.moneymanager.domain.model.calendar

import kotlinx.serialization.Serializable

/**
 * One location as the strategy catalog publishes it and the strategy library syncs it: a [calendar] keyed by its
 * code. The catalog renders it from Strata at publish time, so an installed app picks up a holiday announced after it
 * shipped; installing replaces the database's copy of that location.
 */
@Serializable
data class HolidayCalendarExport(
    /** App version the artifact was exported under; blanked when hashing for semantic comparison. */
    val version: String = "",
    val calendar: HolidayCalendar,
)
