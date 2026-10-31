package com.moneymanager.tools.strategycatalog

import com.moneymanager.domain.model.calendar.HolidayCalendar
import com.opengamma.strata.basics.date.HolidayCalendarIds
import com.opengamma.strata.basics.date.HolidayCalendars
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import java.time.LocalDate as JavaLocalDate

/**
 * A Strata holiday calendar as a location: Strata knows only the [id] and the days, so the name, the zone its days are
 * read in and the countries whose locale suggests it are kept here. [countries] is empty for a calendar no locale
 * should suggest (an exchange's, or a second one for a country another location already serves).
 */
internal data class StrataLocation(
    val id: String,
    val name: String,
    val timeZone: String,
    val countries: List<String> = emptyList(),
)

private val EURO_AREA = "AT BE BG CY DE EE ES FI FR GR HR IE IT LT LU LV MT NL PT SI SK".split(' ')

/** Every Strata calendar the app bundles, in the order pickers list them. */
internal val STRATA_LOCATIONS: List<StrataLocation> =
    listOf(
        StrataLocation("GBLO", "London", "Europe/London", listOf("GB")),
        StrataLocation("EUTA", "TARGET (euro area)", "Europe/Berlin", EURO_AREA),
        StrataLocation("USNY", "New York", "America/New_York", listOf("US")),
        StrataLocation("JPTO", "Tokyo", "Asia/Tokyo", listOf("JP")),
        StrataLocation("CHZU", "Zurich", "Europe/Zurich", listOf("CH", "LI")),
        StrataLocation("AUSY", "Sydney", "Australia/Sydney", listOf("AU")),
        StrataLocation("BRBD", "Brazil", "America/Sao_Paulo", listOf("BR")),
        StrataLocation("CATO", "Toronto", "America/Toronto", listOf("CA")),
        StrataLocation("CZPR", "Prague", "Europe/Prague", listOf("CZ")),
        StrataLocation("DKCO", "Copenhagen", "Europe/Copenhagen", listOf("DK")),
        StrataLocation("HUBU", "Budapest", "Europe/Budapest", listOf("HU")),
        StrataLocation("MXMC", "Mexico City", "America/Mexico_City", listOf("MX")),
        StrataLocation("NOOS", "Oslo", "Europe/Oslo", listOf("NO")),
        StrataLocation("NZBD", "New Zealand", "Pacific/Auckland", listOf("NZ")),
        StrataLocation("PLWA", "Warsaw", "Europe/Warsaw", listOf("PL")),
        StrataLocation("SEST", "Stockholm", "Europe/Stockholm", listOf("SE")),
        StrataLocation("THBA", "Bangkok", "Asia/Bangkok", listOf("TH")),
        StrataLocation("ZAJO", "Johannesburg", "Africa/Johannesburg", listOf("ZA")),
        StrataLocation("FRPA", "Paris", "Europe/Paris"),
        StrataLocation("DEFR", "Frankfurt", "Europe/Berlin"),
        StrataLocation("CAMO", "Montreal", "America/Toronto"),
        StrataLocation("NZAU", "Auckland", "Pacific/Auckland"),
        StrataLocation("NZWE", "Wellington", "Pacific/Auckland"),
        StrataLocation("USGS", "US government securities", "America/New_York"),
        StrataLocation("NYFD", "New York Fed", "America/New_York"),
        StrataLocation("NYSE", "New York Stock Exchange", "America/New_York"),
    )

/** The years Strata's generated calendars cover (`GlobalHolidayCalendars`); outside them it knows only weekends. */
private const val FIRST_YEAR = 1950
private const val LAST_YEAR = 2099

// A weekday Strata closes this often is a weekend day; the rest of its closures are holidays.
private const val WEEKEND_SHARE = 0.9

/** Strata's ids for every calendar with holidays, so a calendar Strata adds can't be left out unnoticed. */
internal fun strataCalendarIds(): Set<String> =
    HolidayCalendars
        .extendedEnum()
        .lookupAll()
        .values
        .map { it.name }
        .filter { it !in WEEKEND_ONLY_IDS }
        .toSet()

private val WEEKEND_ONLY_IDS =
    setOf(HolidayCalendarIds.NO_HOLIDAYS, HolidayCalendarIds.SAT_SUN, HolidayCalendarIds.FRI_SAT, HolidayCalendarIds.THU_FRI)
        .map { it.name }

/** Each of [STRATA_LOCATIONS] as Strata's data gives it. */
internal fun strataHolidayCalendars(): List<HolidayCalendar> = STRATA_LOCATIONS.map(::strataHolidayCalendar)

private fun strataHolidayCalendar(location: StrataLocation): HolidayCalendar {
    val strata = HolidayCalendars.of(location.id)
    val days =
        generateSequence(JavaLocalDate.of(FIRST_YEAR, 1, 1)) { it.plusDays(1) }
            .takeWhile { it.year <= LAST_YEAR }
            .toList()
    val closed = days.filter(strata::isHoliday).toSet()
    val weekend =
        java.time.DayOfWeek
            .entries
            .filter { weekday ->
                val ofWeekday = days.filter { it.dayOfWeek == weekday }
                ofWeekday.count { it in closed } >= ofWeekday.size * WEEKEND_SHARE
            }.toSet()
    return HolidayCalendar(
        id = location.id,
        name = location.name,
        timeZone = location.timeZone,
        countries = location.countries.sorted(),
        weekend = weekend.map { DayOfWeek.valueOf(it.name) }.sorted(),
        holidays = days.filter { it in closed && it.dayOfWeek !in weekend }.map { it.toKotlin() },
        workingDays = days.filter { it !in closed && it.dayOfWeek in weekend }.map { it.toKotlin() },
    )
}

private fun JavaLocalDate.toKotlin(): LocalDate = LocalDate(year, monthValue, dayOfMonth)

/** Renders [calendars] as the Kotlin source of `StrataHolidayCalendars.kt`. */
internal fun renderStrataHolidayCalendarsSource(calendars: List<HolidayCalendar>): String =
    buildString {
        appendLine("package com.moneymanager.domain.model.calendar")
        appendLine()
        appendLine("// GENERATED by `bazel run //tools/strategy-catalog:generate_bundled_calendars` from OpenGamma Strata's")
        appendLine("// holiday calendars (https://github.com/OpenGamma/Strata, Apache License 2.0). Do not edit by hand;")
        appendLine("// StrataHolidayCalendarTableTest fails when this falls behind the Strata release in MODULE.bazel.")
        appendLine()
        appendLine("/**")
        appendLine(" * The locations bundled into the app: OpenGamma Strata's holiday calendars, $FIRST_YEAR–$LAST_YEAR. New databases are")
        appendLine(" * seeded from this on every platform. The strategy catalog publishes each location too, rendered from Strata when it")
        appendLine(" * is published, so a holiday announced after the app shipped reaches it there; Settings edits a database's own copy")
        appendLine(" * (a location Strata doesn't cover).")
        appendLine(" */")
        appendLine("object StrataHolidayCalendars {")
        appendLine("    val all: List<HolidayCalendar> by lazy {")
        appendLine("        listOf(")
        for (calendar in calendars) {
            appendLine("            bundledCalendar(")
            appendLine("                id = \"${calendar.id}\",")
            appendLine("                name = \"${calendar.name}\",")
            appendLine("                timeZone = \"${calendar.timeZone}\",")
            appendLine("                countries = \"${calendar.countries.joinToString(" ")}\",")
            appendLine("                weekend = \"${calendar.weekend.joinToString(" ") { it.name }}\",")
            appendLine("                holidays = ${calendar.id}_HOLIDAYS,")
            appendLine("                workingDays = ${calendar.id}_WORKING_DAYS,")
            appendLine("            ),")
        }
        appendLine("        )")
        appendLine("    }")
        for (calendar in calendars) {
            appendDateConstant("${calendar.id}_HOLIDAYS", calendar.holidays)
            appendDateConstant("${calendar.id}_WORKING_DAYS", calendar.workingDays)
        }
        appendLine("}")
    }

// Eight dates a line keeps the source within ktlint's line length; a constant stays well under the JVM's 64KB.
private const val DATES_PER_LINE = 8

private fun StringBuilder.appendDateConstant(
    name: String,
    dates: List<LocalDate>,
) {
    appendLine()
    appendLine("    private const val $name =")
    val lines = dates.chunked(DATES_PER_LINE)
    if (lines.isEmpty()) appendLine("        \"\"")
    lines.forEachIndexed { index, chunk ->
        val indent = if (index == 0) "        " else "            "
        val separator = if (index == lines.lastIndex) "" else " +"
        appendLine("$indent\"${chunk.joinToString(" ")} \"$separator")
    }
}
