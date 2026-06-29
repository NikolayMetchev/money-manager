package com.moneymanager.csvimporter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DateFormatDetectorTest {
    @Test
    fun `parses checks match the sample against the given pattern`() {
        assertTrue(DateFormatDetector.parsesAsDateTime("yyyy-MM-dd HH:mm:ss", "2026-06-14 13:38:54"))
        assertFalse(DateFormatDetector.parsesAsDateTime("yyyy-MM-dd HH:mm:ss", "14/06/2026"))
        assertTrue(DateFormatDetector.parsesAsDate("dd/MM/yyyy", " 14/06/2026 "))
        assertFalse(DateFormatDetector.parsesAsDate("dd/MM/yyyy", "2026-06-14"))
        assertTrue(DateFormatDetector.parsesAsTime("HH:mm:ss", "13:38:54"))
        assertFalse(DateFormatDetector.parsesAsTime("HH:mm:ss", "not a time"))
    }

    @Test
    fun `parses returns false for an invalid pattern instead of throwing`() {
        assertFalse(DateFormatDetector.parsesAsDate("nonsense pattern", "2026-06-14"))
    }

    @Test
    fun `detects crypto-com combined timestamp`() {
        val samples = listOf("2026-06-14 13:38:54", "2026-05-01 06:59:26", "2026-06-14 11:15:34")
        assertEquals("yyyy-MM-dd HH:mm:ss", DateFormatDetector.detectDateTime(samples))
    }

    @Test
    fun `detects ISO T-separated timestamp`() {
        assertEquals("yyyy-MM-dd'T'HH:mm:ss", DateFormatDetector.detectDateTime(listOf("2026-06-14T13:38:54")))
    }

    @Test
    fun `day-first beats month-first for unambiguous dates`() {
        assertEquals("dd/MM/yyyy", DateFormatDetector.detectDate(listOf("14/06/2026", "01/02/2026")))
    }

    @Test
    fun `falls through to month-first when day-first cannot parse`() {
        // 13 is not a valid month, so dd/MM/yyyy is ruled out and MM/dd/yyyy wins.
        assertEquals("MM/dd/yyyy", DateFormatDetector.detectDate(listOf("06/13/2026")))
    }

    @Test
    fun `detects ISO date`() {
        assertEquals("yyyy-MM-dd", DateFormatDetector.detectDate(listOf("2026-06-14", "2026-01-02")))
    }

    @Test
    fun `detects time formats`() {
        assertEquals("HH:mm:ss", DateFormatDetector.detectTime(listOf("13:38:54")))
        assertEquals("HH:mm", DateFormatDetector.detectTime(listOf("13:38")))
    }

    @Test
    fun `trims surrounding whitespace before matching`() {
        assertEquals("yyyy-MM-dd HH:mm:ss", DateFormatDetector.detectDateTime(listOf("  2026-06-14 13:38:54  ")))
    }

    @Test
    fun `ignores blank samples but requires at least one value`() {
        assertEquals("yyyy-MM-dd", DateFormatDetector.detectDate(listOf("", "2026-06-14", "   ")))
        assertNull(DateFormatDetector.detectDate(listOf("", "   ")))
        assertNull(DateFormatDetector.detectDate(emptyList()))
    }

    @Test
    fun `returns null when no candidate parses every sample`() {
        assertNull(DateFormatDetector.detectDate(listOf("not a date")))
        // Mixed layouts: no single pattern parses both.
        assertNull(DateFormatDetector.detectDate(listOf("2026-06-14", "14/06/2026")))
    }
}
