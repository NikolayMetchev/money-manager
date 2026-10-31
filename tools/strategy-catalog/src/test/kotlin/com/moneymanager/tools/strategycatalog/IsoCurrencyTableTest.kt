package com.moneymanager.tools.strategycatalog

import com.moneymanager.database.json.CurrencyListExportCodec
import com.moneymanager.domain.model.CurrencyExportEntry
import com.moneymanager.domain.strategy.StrategyFileNaming
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.domain.strategy.StrategyKind
import kotlin.test.Test
import kotlin.test.assertEquals

class IsoCurrencyTableTest {
    @Test
    fun `the published currency list is this JDK's with each code's minor unit`() {
        val key = StrategyKey(StrategyKind.CURRENCIES, StrategyFileNaming.CURRENCIES_NAME)
        val published = CurrencyListExportCodec.decode(builtInArtifacts().getValue(key)).currencies
        assertEquals(jdkIsoCurrencies(), published)
        val byCode = published.associateBy { it.code }
        assertEquals(CurrencyExportEntry("GBP", "British Pound", 2), byCode["GBP"])
        assertEquals(0, byCode.getValue("JPY").fractionDigits)
        assertEquals(3, byCode.getValue("KWD").fractionDigits)
        assertEquals(null, byCode.getValue("XAU").fractionDigits)
    }
}
