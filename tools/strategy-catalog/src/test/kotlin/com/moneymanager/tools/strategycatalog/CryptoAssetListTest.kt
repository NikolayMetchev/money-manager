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

class CryptoAssetListTest {
    private val usd = CurrencyExportEntry("USD", "US Dollar", 2)

    @Test
    fun `an alias row is no asset and its target carries it`() {
        val rows =
            listOf(
                CryptoDatasetEntry("LUNA", "Terra"),
                CryptoDatasetEntry("LUNC", "Terra Classic", aliasOf = "LUNA"),
                CryptoDatasetEntry("ZUSD", "Kraken dollar", aliasOf = "USD"),
            )
        val assets = cryptoAssetListExport(rows, listOf(usd)).assets.associateBy { it.code }

        assertEquals(setOf("LUNA", "USD"), assets.keys)
        assertEquals(setOf("LUNC"), assets.getValue("LUNA").aliases)
        assertEquals(setOf("ZUSD"), assets.getValue("USD").aliases)
        assertEquals(AssetExportKind.CURRENCY, assets.getValue("USD").kind)
        assertEquals(2, assets.getValue("USD").fractionDigits)
    }

    @Test
    fun `a coin sharing an ISO code is left out`() {
        val assets = cryptoAssetListExport(listOf(CryptoDatasetEntry("USD", "A token")), listOf(usd)).assets
        assertTrue(assets.isEmpty())
    }

    @Test
    fun `an alias naming nothing fails the build`() {
        assertFailsWith<IllegalArgumentException> {
            cryptoAssetListExport(listOf(CryptoDatasetEntry("X", "X", aliasOf = "NOPE")), emptyList())
        }
    }

    @Test
    fun `the published list has the coins and aliases of the dataset`() {
        val json = builtInArtifacts().getValue(StrategyKey(StrategyKind.CRYPTO_ASSETS, StrategyFileNaming.CRYPTO_ASSETS_NAME))
        val assets = AssetListExportCodec.decode(json).assets.associateBy { it.code }

        assertEquals(setOf("LUNC"), assets.getValue("LUNA").aliases)
        assertEquals(setOf("XBT", "XXBT"), assets.getValue("BTC").aliases)
        assertEquals(setOf("ZUSD"), assets.getValue("USD").aliases)
        assertFalse("LUNC" in assets)
        assertTrue(assets.size > BundledCryptoCatalog.rows.size / 2)
    }
}
