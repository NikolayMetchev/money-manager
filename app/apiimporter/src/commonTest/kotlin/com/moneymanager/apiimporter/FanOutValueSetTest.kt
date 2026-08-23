package com.moneymanager.apiimporter

import com.moneymanager.domain.model.apistrategy.ApiValueSet
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [resolveValueSet] — the generic fan-out value resolution used by
 * [com.moneymanager.domain.model.apistrategy.ApiFanOut] (e.g. Binance `myTrades`'s per-symbol sweep).
 */
class FanOutValueSetTest {
    private fun obj(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    @Test
    fun `static values pass through, uppercased and blank-filtered`() {
        val result = resolveValueSet(ApiValueSet.Static(listOf("usdt", "", "btc")), emptyMap(), emptyMap())
        assertEquals(listOf("USDT", "BTC"), result)
    }

    @Test
    fun `from-value-endpoint reads every configured field across every item`() {
        val values =
            mapOf(
                "getUserAsset" to
                    listOf(obj("asset" to "btc"), obj("asset" to "eth")),
            )
        val result = resolveValueSet(ApiValueSet.FromValueEndpoint("getUserAsset", listOf("asset")), values, emptyMap())
        assertEquals(listOf("BTC", "ETH"), result)
    }

    @Test
    fun `from-data-endpoint reads several fields per item, unioned`() {
        val data =
            mapOf(
                "convert" to listOf(obj("fromAsset" to "usdt", "toAsset" to "bnb")),
            )
        val result = resolveValueSet(ApiValueSet.FromDataEndpoint("convert", listOf("fromAsset", "toAsset")), emptyMap(), data)
        assertEquals(setOf("USDT", "BNB"), result.toSet())
    }

    @Test
    fun `union combines several sets`() {
        val result =
            resolveValueSet(
                ApiValueSet.Union(listOf(ApiValueSet.Static(listOf("btc")), ApiValueSet.Static(listOf("eth")))),
                emptyMap(),
                emptyMap(),
            )
        assertEquals(setOf("BTC", "ETH"), result.toSet())
    }

    @Test
    fun `cross product substitutes the template for every left-right pair`() {
        val result =
            resolveValueSet(
                ApiValueSet.CrossProduct(
                    left = ApiValueSet.Static(listOf("btc", "eth")),
                    right = ApiValueSet.Static(listOf("usdt", "gbp")),
                    template = "{left}{right}",
                ),
                emptyMap(),
                emptyMap(),
            )
        assertEquals(setOf("BTCUSDT", "BTCGBP", "ETHUSDT", "ETHGBP"), result.toSet())
    }

    @Test
    fun `an unknown endpoint path resolves to no values rather than throwing`() {
        val result = resolveValueSet(ApiValueSet.FromValueEndpoint("missing", listOf("asset")), emptyMap(), emptyMap())
        assertEquals(emptyList(), result)
    }

    @Test
    fun `validAgainst intersection drops a candidate not in the real symbol universe`() {
        val candidates =
            resolveValueSet(
                ApiValueSet.CrossProduct(
                    left = ApiValueSet.Static(listOf("btc", "doge")),
                    right = ApiValueSet.Static(listOf("gbp")),
                    template = "{left}{right}",
                ),
                emptyMap(),
                emptyMap(),
            )
        // exchangeInfo's responseArrayKey ("symbols") already unwraps the response to individual
        // symbol objects before they're stored - each item's own "symbol" field is read directly.
        val validSymbols =
            resolveValueSet(
                ApiValueSet.FromValueEndpoint("exchangeInfo", listOf("symbol")),
                mapOf("exchangeInfo" to listOf(obj("symbol" to "BTCGBP"))),
                emptyMap(),
            ).toSet()
        val surviving = candidates.filter { it in validSymbols }
        assertEquals(listOf("BTCGBP"), surviving)
    }
}
