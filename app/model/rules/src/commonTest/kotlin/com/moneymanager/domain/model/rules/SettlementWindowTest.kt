package com.moneymanager.domain.model.rules

import com.moneymanager.domain.model.calendar.StrataHolidayCalendars
import com.moneymanager.domain.model.calendar.WeekendOnlyCalendar
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class SettlementWindowTest {
    private val london = StrataHolidayCalendars.all.single { it.id == "GBLO" }
    private val threeDays = SettlementWindow.BusinessDays(3)

    private fun at(text: String) = Instant.parse(text)

    @Test
    fun `an elapsed window is clock time on any calendar`() {
        val window = SettlementWindow.Elapsed(3600)
        assertTrue(window.contains(at("2021-04-01T10:00:00Z"), at("2021-04-01T11:00:00Z"), london))
        assertFalse(window.contains(at("2021-04-01T11:00:01Z"), at("2021-04-01T10:00:00Z"), london))
    }

    @Test
    fun `three business days span Easter in London in either order`() {
        val thursday = at("2021-04-01T09:00:00Z")
        val easterMonday = at("2021-04-05T09:00:00Z")
        assertTrue(threeDays.contains(thursday, easterMonday, london))
        assertTrue(threeDays.contains(easterMonday, thursday, london))
        // Saturday and Sunday off only: Good Friday and Easter Monday count, Monday is the 2nd business day.
        assertTrue(threeDays.contains(thursday, easterMonday, WeekendOnlyCalendar))
        assertFalse(SettlementWindow.BusinessDays(1).contains(thursday, easterMonday, WeekendOnlyCalendar))
        assertTrue(SettlementWindow.BusinessDays(0).contains(thursday, easterMonday, london))
    }

    @Test
    fun `three business days reject the same movement a week later`() {
        assertFalse(threeDays.contains(at("2021-06-07T09:00:00Z"), at("2021-06-14T09:00:00Z"), london))
        assertTrue(threeDays.contains(at("2021-06-07T09:00:00Z"), at("2021-06-10T22:00:00Z"), london))
        // 23:30 UTC on the 10th is already the 11th in London (BST).
        assertFalse(threeDays.contains(at("2021-06-07T09:00:00Z"), at("2021-06-10T23:30:00Z"), london))
    }

    @Test
    fun `ranges meet when they overlap or their nearest ends are in the window`() {
        val window = SettlementWindow.Elapsed(60)
        val start = at("2021-04-01T10:00:00Z")
        assertTrue(window.meets(start, at("2021-04-01T12:00:00Z"), at("2021-04-01T11:00:00Z"), null, london))
        assertTrue(window.meets(start, null, at("2021-04-01T10:01:00Z"), null, london))
        assertTrue(window.meets(at("2021-04-01T10:01:00Z"), null, at("2021-04-01T09:00:00Z"), start, london))
        assertFalse(window.meets(start, null, at("2021-04-01T10:01:01Z"), null, london))
    }

    @Test
    fun `widening covers every instant the window could match`() {
        val from = at("2021-04-01T09:00:00Z")
        val range = threeDays.widen(from, from, london)
        assertTrue(at("2021-03-29T00:00:00Z") in range)
        assertTrue(at("2021-04-08T22:59:59Z") in range)
        for (hours in -400..400) {
            val other = from + hours.hours
            if (threeDays.contains(from, other, london)) assertTrue(other in range, "$other")
        }
        assertEquals(at("2021-04-01T08:00:00Z")..at("2021-04-01T10:00:00Z"), SettlementWindow.Elapsed(3600).widen(from, from, london))
    }

    @Test
    fun `windows serialize by kind`() {
        val windows = listOf<SettlementWindow>(SettlementWindow.Elapsed(300), threeDays)
        val json = Json.encodeToString(windows)
        assertEquals("""[{"type":"elapsed","seconds":300},{"type":"businessDays","days":3}]""", json)
        assertEquals(windows, Json.decodeFromString<List<SettlementWindow>>(json))
    }
}
