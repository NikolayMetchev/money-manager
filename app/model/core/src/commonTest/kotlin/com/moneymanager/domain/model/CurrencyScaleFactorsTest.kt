package com.moneymanager.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class CurrencyScaleFactorsTest {
    @Test
    fun getScaleFactor_isTheSameForEveryCurrency() {
        // Every currency shares crypto's scale, not an ISO-4217-derived one — an exchange-reported
        // amount can legitimately carry more precision than a currency's nominal decimal places.
        assertEquals(CryptoAsset.CRYPTO_SCALE_FACTOR, CurrencyScaleFactors.getScaleFactor("USD"))
        assertEquals(CryptoAsset.CRYPTO_SCALE_FACTOR, CurrencyScaleFactors.getScaleFactor("GBP"))
        assertEquals(CryptoAsset.CRYPTO_SCALE_FACTOR, CurrencyScaleFactors.getScaleFactor("JPY"))
        assertEquals(CryptoAsset.CRYPTO_SCALE_FACTOR, CurrencyScaleFactors.getScaleFactor("BHD"))
        assertEquals(CryptoAsset.CRYPTO_SCALE_FACTOR, CurrencyScaleFactors.getScaleFactor("XYZ"))
    }

    @Test
    fun getScaleFactor_isCaseInsensitive() {
        assertEquals(CryptoAsset.CRYPTO_SCALE_FACTOR, CurrencyScaleFactors.getScaleFactor("usd"))
        assertEquals(CryptoAsset.CRYPTO_SCALE_FACTOR, CurrencyScaleFactors.getScaleFactor("gbp"))
    }

    @Test
    fun getDecimalPlaces_matchesTheSharedScaleFactor() {
        assertEquals(18, CurrencyScaleFactors.getDecimalPlaces("USD"))
        assertEquals(18, CurrencyScaleFactors.getDecimalPlaces("GBP"))
        assertEquals(18, CurrencyScaleFactors.getDecimalPlaces("JPY"))
    }
}
