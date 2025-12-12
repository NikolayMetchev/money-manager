package com.moneymanager.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class CurrencyScaleFactorsTest {
    @Test
    fun getScaleFactor_returnsCorrectValueForStandardCurrencies() {
        // 2 decimal places (scale factor 100)
        assertEquals(100, CurrencyScaleFactors.getScaleFactor("USD"))
        assertEquals(100, CurrencyScaleFactors.getScaleFactor("EUR"))
        assertEquals(100, CurrencyScaleFactors.getScaleFactor("GBP"))
        assertEquals(100, CurrencyScaleFactors.getScaleFactor("CHF"))
    }

    @Test
    fun getScaleFactor_returnsCorrectValueForZeroDecimalCurrencies() {
        // 0 decimal places (scale factor 1)
        assertEquals(1, CurrencyScaleFactors.getScaleFactor("JPY"))
        assertEquals(1, CurrencyScaleFactors.getScaleFactor("KRW"))
        assertEquals(1, CurrencyScaleFactors.getScaleFactor("VND"))
        assertEquals(1, CurrencyScaleFactors.getScaleFactor("CLP"))
    }

    @Test
    fun getScaleFactor_returnsCorrectValueForThreeDecimalCurrencies() {
        // 3 decimal places (scale factor 1000)
        assertEquals(1000, CurrencyScaleFactors.getScaleFactor("BHD"))
        assertEquals(1000, CurrencyScaleFactors.getScaleFactor("KWD"))
        assertEquals(1000, CurrencyScaleFactors.getScaleFactor("JOD"))
        assertEquals(1000, CurrencyScaleFactors.getScaleFactor("OMR"))
    }

    @Test
    fun getScaleFactor_returnsDefaultForUnknownCurrency() {
        assertEquals(
            CurrencyScaleFactors.DEFAULT_SCALE_FACTOR,
            CurrencyScaleFactors.getScaleFactor("XYZ"),
        )
    }

    @Test
    fun getScaleFactor_isCaseInsensitive() {
        assertEquals(100, CurrencyScaleFactors.getScaleFactor("usd"))
        assertEquals(1, CurrencyScaleFactors.getScaleFactor("jpy"))
        assertEquals(1000, CurrencyScaleFactors.getScaleFactor("bhd"))
    }

    @Test
    fun getDecimalPlaces_returnsCorrectValueForStandardCurrencies() {
        // 2 decimal places
        assertEquals(2, CurrencyScaleFactors.getDecimalPlaces("USD"))
        assertEquals(2, CurrencyScaleFactors.getDecimalPlaces("EUR"))
        assertEquals(2, CurrencyScaleFactors.getDecimalPlaces("GBP"))
    }

    @Test
    fun getDecimalPlaces_returnsCorrectValueForZeroDecimalCurrencies() {
        // 0 decimal places
        assertEquals(0, CurrencyScaleFactors.getDecimalPlaces("JPY"))
        assertEquals(0, CurrencyScaleFactors.getDecimalPlaces("KRW"))
    }

    @Test
    fun getDecimalPlaces_returnsCorrectValueForThreeDecimalCurrencies() {
        // 3 decimal places
        assertEquals(3, CurrencyScaleFactors.getDecimalPlaces("BHD"))
        assertEquals(3, CurrencyScaleFactors.getDecimalPlaces("KWD"))
    }

    @Test
    fun getDecimalPlaces_returnsDefaultForUnknownCurrency() {
        assertEquals(2, CurrencyScaleFactors.getDecimalPlaces("XYZ"))
    }
}
