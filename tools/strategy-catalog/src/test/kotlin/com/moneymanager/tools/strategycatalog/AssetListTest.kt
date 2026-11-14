package com.moneymanager.tools.strategycatalog

import com.moneymanager.cryptodata.BundledCryptoCatalog
import com.moneymanager.cryptodata.CryptoDatasetEntry
import com.moneymanager.database.json.AssetListExportCodec
import com.moneymanager.domain.model.AssetExportKind
import com.moneymanager.domain.model.CurrencyExportEntry
import com.moneymanager.domain.strategy.StrategyFileNaming
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.domain.strategy.StrategyKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssetListTest {
    private val usd = CurrencyExportEntry("USD", "US Dollar", 2, setOf("U.S. DOLLAR"))
    private val eur = CurrencyExportEntry("EUR", "Euro", 2)

    @Test
    fun `an alias row is no asset and its target carries it`() {
        val rows =
            listOf(
                CryptoDatasetEntry("LUNA", "Terra"),
                CryptoDatasetEntry("LUNC", "Terra Classic", aliasOf = "LUNA"),
                CryptoDatasetEntry("ZUSD", "Kraken dollar", aliasOf = "USD"),
            )
        val assets = assetListExport(rows, listOf(usd, eur)).assets.associateBy { it.code }

        assertEquals(setOf("LUNA", "USD", "EUR"), assets.keys)
        assertEquals(setOf("LUNC"), assets.getValue("LUNA").aliases)
        assertEquals(setOf("U.S. DOLLAR", "ZUSD"), assets.getValue("USD").aliases)
        assertEquals(AssetExportKind.CURRENCY, assets.getValue("USD").kind)
        assertEquals(2, assets.getValue("USD").fractionDigits)
        assertEquals(AssetExportKind.CURRENCY, assets.getValue("EUR").kind, "every currency is listed, aliased or not")
    }

    @Test
    fun `a coin sharing an ISO code is left out`() {
        val assets = assetListExport(listOf(CryptoDatasetEntry("USD", "A token")), listOf(usd)).assets
        assertEquals(listOf(AssetExportKind.CURRENCY), assets.map { it.kind })
    }

    @Test
    fun `an alias naming nothing fails the build`() {
        assertFailsWith<IllegalArgumentException> {
            assetListExport(listOf(CryptoDatasetEntry("X", "X", aliasOf = "NOPE")), emptyList())
        }
    }

    @Test
    fun `the published list has this JDK's currencies and the dataset's coins and aliases`() {
        val json = builtInArtifacts().getValue(StrategyKey(StrategyKind.ASSET_LIST, StrategyFileNaming.ASSET_LIST_NAME))
        val (currencies, coins) = AssetListExportCodec.decode(json).assets.partition { it.kind == AssetExportKind.CURRENCY }
        val coinsByCode = coins.associateBy { it.code }
        val currenciesByCode = currencies.associateBy { it.code }

        assertEquals(jdkIsoCurrencies().map { it.code }.toSet(), currenciesByCode.keys)
        assertEquals(setOf("POUND STERLING", "ZGBP"), currenciesByCode.getValue("GBP").aliases)
        assertEquals(2, currenciesByCode.getValue("GBP").fractionDigits)
        assertEquals(setOf("U.S. DOLLAR", "ZUSD"), currenciesByCode.getValue("USD").aliases)
        assertEquals(0, currenciesByCode.getValue("JPY").fractionDigits)
        assertEquals(3, currenciesByCode.getValue("KWD").fractionDigits)
        assertEquals(null, currenciesByCode.getValue("XAU").fractionDigits)

        assertEquals(setOf("LUNC"), coinsByCode.getValue("LUNA").aliases)
        assertEquals(setOf("XBT", "XXBT"), coinsByCode.getValue("BTC").aliases)
        assertFalse("LUNC" in coinsByCode)
        assertTrue(coins.size > BundledCryptoCatalog.rows.size / 2)
    }
}
