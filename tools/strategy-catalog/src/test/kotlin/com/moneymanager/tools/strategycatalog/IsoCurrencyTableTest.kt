package com.moneymanager.tools.strategycatalog

import com.moneymanager.currency.IsoCurrencies
import com.moneymanager.database.json.CurrencyListExportCodec
import com.moneymanager.domain.strategy.StrategyFileNaming
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.domain.strategy.StrategyKind
import kotlin.test.Test
import kotlin.test.assertEquals

class IsoCurrencyTableTest {
    @Test
    fun `the bundled currency table matches this JDK`() {
        assertEquals(
            jdkIsoCurrencies(),
            IsoCurrencies.all,
            "utils/currency's IsoCurrencies.kt is stale: run bazel run //tools/strategy-catalog:generate_bundled_currencies",
        )
    }

    @Test
    fun `the published currency list carries every bundled code`() {
        val key = StrategyKey(StrategyKind.CURRENCIES, StrategyFileNaming.CURRENCIES_NAME)
        val published = CurrencyListExportCodec.decode(builtInArtifacts().getValue(key)).currencies
        assertEquals(IsoCurrencies.all.map { it.code to it.name }, published.map { it.code to it.name })
    }
}
