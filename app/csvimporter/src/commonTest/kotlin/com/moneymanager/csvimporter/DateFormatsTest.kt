package com.moneymanager.csvimporter

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DateFormatsTest {
    private val formats = DateFormats()

    private fun reference(date: LocalDate): () -> LocalDate = { date }

    @Test
    fun `a 12-hour clock reads midnight noon and the afternoon`() {
        val pattern = "MMMM d, yyyy h:mm a"
        assertEquals(LocalDateTime(2022, 7, 14, 0, 0), formats.dateTime("July 14, 2022 12:00 AM", pattern))
        assertEquals(LocalDateTime(2022, 7, 8, 5, 0), formats.dateTime("July 8, 2022 5:00 AM", pattern))
        assertEquals(LocalDateTime(2022, 7, 8, 12, 30), formats.dateTime("July 8, 2022 12:30 PM", pattern))
        assertEquals(LocalDateTime(2022, 6, 11, 22, 38), formats.dateTime("June 11, 2022 10:38 pm", pattern))
    }

    @Test
    fun `a 12-hour clock also reads numeric dates seconds and a separate time column`() {
        assertEquals(LocalDateTime(2022, 6, 11, 22, 38, 5), formats.dateTime("2022-06-11 10:38:05PM", "yyyy-MM-dd hh:mm:ssa"))
        assertEquals(LocalTime(17, 9), formats.time(" 5:09 PM ", "h:mm a"))
    }

    @Test
    fun `an hour a 12-hour clock lacks fails to parse`() {
        assertFailsWith<IllegalArgumentException> { formats.time("13:00 PM", "h:mm a") }
        assertFailsWith<IllegalArgumentException> { formats.time("0:30 AM", "h:mm a") }
        assertFailsWith<IllegalArgumentException> { formats.time("5:09", "h:mm a") }
        assertFailsWith<IllegalArgumentException> { formats.time("17:09", "h:mm a") }
    }

    @Test
    fun `month names read in either case and length`() {
        assertEquals(LocalDate(2008, 2, 28), formats.date("February 28 2008", "MMMM d yyyy"))
        assertEquals(LocalDate(2013, 1, 2), formats.date("2 JAN  2013", "d MMM yyyy"))
        assertEquals(LocalDate(2013, 9, 2), formats.date("2 Sept 2013", "d MMM yyyy"))
    }

    @Test
    fun `a yearless date takes the reference date's year`() {
        assertEquals(LocalDate(2013, 1, 2), formats.date("2 Jan", "d MMM", reference(LocalDate(2013, 1, 19))))
    }

    @Test
    fun `a yearless date after the reference date is last year's`() {
        assertEquals(LocalDate(2012, 12, 20), formats.date("20 Dec", "d MMM", reference(LocalDate(2013, 1, 19))))
    }

    @Test
    fun `the reference date itself is this year's`() {
        assertEquals(LocalDate(2013, 1, 19), formats.date("19 Jan", "d MMM", reference(LocalDate(2013, 1, 19))))
    }

    @Test
    fun `29 February falls back to the leap year before`() {
        assertEquals(LocalDate(2012, 2, 29), formats.date("29 Feb", "d MMM", reference(LocalDate(2013, 3, 19))))
        assertEquals(LocalDate(2016, 2, 29), formats.date("29 Feb", "d MMM", reference(LocalDate(2016, 3, 19))))
    }

    @Test
    fun `a yearless pattern needs a reference`() {
        assertFailsWith<IllegalArgumentException> { formats.date("2 Jan", "d MMM") }
    }

    @Test
    fun `numeric patterns are unchanged`() {
        assertEquals(LocalDate(2013, 1, 19), formats.date("19/01/13", "dd/MM/yy"))
    }
}
