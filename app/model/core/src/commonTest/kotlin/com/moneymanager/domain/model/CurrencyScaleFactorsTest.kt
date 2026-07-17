package com.moneymanager.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class CurrencyScaleFactorsTest {
    @Test
    fun defaultScaleFactor_matchesCryptoScale() {
        // Every currency shares crypto's scale, not an ISO-4217-derived one — an exchange-reported
        // amount can legitimately carry more precision than a currency's nominal decimal places.
        assertEquals(CryptoAsset.CRYPTO_SCALE_FACTOR, CurrencyScaleFactors.DEFAULT_SCALE_FACTOR)
    }
}
