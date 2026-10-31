package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.calendar.BusinessDayCalendar
import com.moneymanager.domain.model.calendar.CombinedCalendar
import com.moneymanager.domain.model.calendar.HolidayCalendar
import com.moneymanager.domain.model.calendar.WeekendOnlyCalendar

/**
 * Which days count for a business-day reconcile window between two records: the calendars of the locations of the
 * accounts either record touches (an account's own `location` attribute), combined so a day is a business day only
 * where every one of them is open. Accounts without a location (a merchant, a description-derived placeholder) don't
 * pull in the default; only when none of the accounts has one does the database's [defaultLocation] apply, and
 * without that the week's Saturday and Sunday are the only days off.
 */
class SettlementCalendars(
    private val locations: Map<AccountId, String>,
    private val defaultLocation: String?,
    calendars: List<HolidayCalendar>,
) {
    private val byCode = calendars.associateBy { it.id }
    private val combined = mutableMapOf<List<String>, BusinessDayCalendar>()

    fun forAccounts(accounts: Iterable<AccountId>): BusinessDayCalendar {
        val codes =
            accounts
                .mapNotNull { locations[it] }
                .distinct()
                .sorted()
                .ifEmpty { listOfNotNull(defaultLocation) }
        return calendarOf(codes)
    }

    /**
     * Every location in use at once: no pair's calendar has fewer business days, so walking a window on this one
     * reaches at least as far as any pair's would. What candidate loading widens by.
     */
    val strictest: BusinessDayCalendar by lazy { calendarOf((locations.values + listOfNotNull(defaultLocation)).distinct().sorted()) }

    private fun calendarOf(codes: List<String>): BusinessDayCalendar =
        when (codes.size) {
            0 -> WeekendOnlyCalendar
            1 -> calendar(codes.single())
            else -> combined.getOrPut(codes) { CombinedCalendar(codes.map(::calendar)) }
        }

    private fun calendar(code: String): HolidayCalendar =
        byCode[code] ?: error("Location '$code' has no holiday calendar: add one, or choose another location for its accounts")

    companion object {
        /** No locations: every pair is on a Saturday-and-Sunday weekend. */
        val WEEKENDS_ONLY = SettlementCalendars(emptyMap(), null, emptyList())
    }
}
