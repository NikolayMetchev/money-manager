package com.moneymanager.domain.model.calendar

import com.moneymanager.domain.model.serialization.SortedListSerializer
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.serializers.DayOfWeekSerializer
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.time.Instant

/** Which days a place settles payments on: the one question a business-day window asks. */
interface BusinessDayCalendar {
    /** Where the calendar's days begin and end, so an `Instant` becomes one of its dates. */
    val zone: TimeZone

    fun isBusinessDay(date: LocalDate): Boolean
}

/**
 * A location's holidays, shaped after OpenGamma Strata's `ImmutableHolidayCalendar`: the [weekend] days, every other
 * non-business day listed in [holidays], and the [workingDays] that fall on a weekend yet are business days (Budapest's
 * working Saturdays). [id] is Strata's code for the location (`GBLO` for London, `USNY` for New York), which accounts
 * and the database's default location name. The strategy catalog publishes Strata's, rendered when it is published.
 * [countries] are the ISO 3166 codes the location serves, so a device's locale can suggest one.
 *
 * Outside the years [holidays] covers only the weekend is known, so those days are business days.
 */
@Serializable
data class HolidayCalendar(
    val id: String,
    val name: String,
    val timeZone: String,
    @Serializable(with = SortedStringListSerializer::class)
    val countries: List<String> = emptyList(),
    @Serializable(with = SortedWeekdayListSerializer::class)
    val weekend: List<DayOfWeek> = DEFAULT_WEEKEND,
    @Serializable(with = SortedLocalDateListSerializer::class)
    val holidays: List<LocalDate> = emptyList(),
    @Serializable(with = SortedLocalDateListSerializer::class)
    val workingDays: List<LocalDate> = emptyList(),
) : BusinessDayCalendar {
    override val zone: TimeZone by lazy { TimeZone.of(timeZone) }

    private val holidaySet by lazy { holidays.toSet() }
    private val workingDaySet by lazy { workingDays.toSet() }

    override fun isBusinessDay(date: LocalDate): Boolean = date !in holidaySet && (date.dayOfWeek !in weekend || date in workingDaySet)

    /** "London (GBLO)", as pickers show a location. */
    val label: String get() = if (name.isBlank() || name == id) id else "$name ($id)"

    companion object {
        val DEFAULT_WEEKEND: List<DayOfWeek> = listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    }
}

/**
 * Business days in every one of [calendars], as Strata's `combinedWith`: a payment between a London and a New York
 * account settles only on days both are open. Dates are read in the first calendar's zone, so callers pass them in a
 * stable order (by id) and the same accounts always agree.
 */
class CombinedCalendar(
    val calendars: List<BusinessDayCalendar>,
) : BusinessDayCalendar {
    init {
        require(calendars.isNotEmpty()) { "A combined calendar needs at least one calendar" }
    }

    override val zone: TimeZone get() = calendars.first().zone

    override fun isBusinessDay(date: LocalDate): Boolean = calendars.all { it.isBusinessDay(date) }
}

/** Saturday and Sunday off, nothing else (Strata's `Sat/Sun`): the calendar when no account has a location. */
object WeekendOnlyCalendar : BusinessDayCalendar {
    override val zone: TimeZone = TimeZone.UTC

    override fun isBusinessDay(date: LocalDate): Boolean = date.dayOfWeek !in HolidayCalendar.DEFAULT_WEEKEND
}

private val ONE_DAY = DatePeriod(days = 1)

// A calendar with no business day for ten years is corrupt; stop rather than spin.
private const val MAX_STEPS = 3660

fun BusinessDayCalendar.dateOf(instant: Instant): LocalDate = instant.toLocalDateTime(zone).date

/** The business days in `(earlier, later]`, so the next business day is 1 away whatever lies between. */
fun BusinessDayCalendar.businessDaysBetween(
    earlier: LocalDate,
    later: LocalDate,
): Int {
    var count = 0
    var day = earlier.plus(ONE_DAY)
    while (day <= later) {
        if (isBusinessDay(day)) count++
        day = day.plus(ONE_DAY)
    }
    return count
}

/**
 * The furthest date still within [days] business days of [date]: after it for a positive count, before it for a
 * negative one. The weekend and holidays at the edge are included, so `reach` agrees with [businessDaysBetween]:
 * `businessDaysBetween(date, reach(date, n)) == n` and `businessDaysBetween(reach(date, -n), date) == n`.
 */
fun BusinessDayCalendar.reach(
    date: LocalDate,
    days: Int,
): LocalDate {
    var remaining = if (days < 0) -days else days
    var day = date
    var steps = 0
    if (days >= 0) {
        // Each step adds `next` to (date, next].
        while (true) {
            val next = day.plus(ONE_DAY)
            if (isBusinessDay(next)) {
                if (remaining == 0) return day
                remaining--
            }
            day = next
            check(++steps <= MAX_STEPS) { "No business day within $MAX_STEPS days of $date" }
        }
    }
    // Each step adds `day` to (day - 1, date].
    while (true) {
        if (isBusinessDay(day)) {
            if (remaining == 0) return day
            remaining--
        }
        day = day.minus(ONE_DAY)
        check(++steps <= MAX_STEPS) { "No business day within $MAX_STEPS days of $date" }
    }
}

/** Whether [later] is at most [days] business days after [earlier], without walking a long gap day by day. */
fun BusinessDayCalendar.withinBusinessDays(
    earlier: LocalDate,
    later: LocalDate,
    days: Int,
): Boolean = later <= reach(earlier, days)

object SortedStringListSerializer : SortedListSerializer<String>(String.serializer())

object SortedLocalDateListSerializer : SortedListSerializer<LocalDate>(LocalDate.serializer())

object SortedWeekdayListSerializer : SortedListSerializer<DayOfWeek>(DayOfWeekSerializer)
