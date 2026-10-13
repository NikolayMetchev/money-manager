package com.moneymanager.apiimport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CurrentCountryTest {
    @Test
    fun readsTheCountryOfPosixLocales() {
        assertEquals("GB", countryFromLocaleTag("en_GB.UTF-8"))
        assertEquals("DE", countryFromLocaleTag("de_DE@euro"))
        assertEquals("US", countryFromLocaleTag("en_US"))
    }

    @Test
    fun readsTheCountryOfBcp47Tags() {
        assertEquals("GB", countryFromLocaleTag("en-GB"))
        assertEquals("TW", countryFromLocaleTag("zh-Hant-TW"))
        assertEquals("PH", countryFromLocaleTag("fil-PH"))
    }

    @Test
    fun localesWithoutACountryGiveNull() {
        assertNull(countryFromLocaleTag("C"))
        assertNull(countryFromLocaleTag("POSIX"))
        assertNull(countryFromLocaleTag("C.UTF-8"))
        assertNull(countryFromLocaleTag("en"))
        assertNull(countryFromLocaleTag(""))
        assertNull(countryFromLocaleTag(null))
    }
}
