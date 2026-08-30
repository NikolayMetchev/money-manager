package com.moneymanager.apiimporter

import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.apistrategy.TimestampFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonPrimitivesTest {
    private val json = """{ "result": { "data": [ {"price": "100"}, {"price": "200"} ] }, "code": 0 }"""

    private fun path(p: String): String? = (Json.parseToJsonElement(json).resolveJsonPathElement(p) as? JsonPrimitive)?.contentOrNull

    @Test
    fun `resolves nested object and array paths`() {
        assertEquals("100", path("result.data[0].price"))
        assertEquals("200", path("result.data[1].price"))
        assertEquals("0", path("code"))
        assertNull(path("result.data[2].price"))
        assertNull(path("result.missing"))
        // Malformed segment with trailing text after the brackets resolves to null, not data[0].
        assertNull(path("result.data[0]foo"))
    }

    @Test
    fun `success code check honors envelope status`() {
        val ok = """{ "code": 0, "result": {} }"""
        val bad = """{ "code": 40004, "message": "bad" }"""
        assertEquals(true, responseCodeOk(ok, "code", "0"))
        assertEquals(false, responseCodeOk(bad, "code", "0"))
        // No configured field: never gates (bank APIs rely on HTTP status).
        assertEquals(true, responseCodeOk(bad, null, null))
        // A configured field with no expected value fails closed (never passes an error envelope).
        assertEquals(false, responseCodeOk(ok, "code", null))
    }

    @Test
    fun `error array check honors an empty or absent array as success (Kraken)`() {
        val ok = """{ "error": [], "result": { "trades": {} } }"""
        val okAbsent = """{ "result": { "trades": {} } }"""
        val bad = """{ "error": ["EGeneral:Invalid arguments"] }"""
        assertEquals(true, responseCodeOk(ok, null, null, errorArrayField = "error"))
        assertEquals(true, responseCodeOk(okAbsent, null, null, errorArrayField = "error"))
        assertEquals(false, responseCodeOk(bad, null, null, errorArrayField = "error"))
    }

    @Test
    fun `keyed-object response array splices the map key into each item`() {
        // Kraken's TradesHistory/Ledgers shape: an object keyed by id, not an array.
        val json = """{ "result": { "ledger": {
            "LG1": { "asset": "XXBT", "amount": "0.01" },
            "LG2": { "asset": "ZUSD", "amount": "100" }
        } } }"""
        val items = responseItemsArray(json, "result.ledger", responseObjectValues = true, itemKeyField = "ledger_id")
        assertEquals(2, items?.size)
        val byAsset =
            items
                .orEmpty()
                .associate { entry ->
                    val obj = entry as JsonObject
                    (obj["asset"] as JsonPrimitive).content to (obj["ledger_id"] as JsonPrimitive).content
                }
        assertEquals(mapOf("XXBT" to "LG1", "ZUSD" to "LG2"), byAsset)

        // Without itemKeyField, items pass through unmodified (still splits the object into values).
        val plain = responseItemsArray(json, "result.ledger", responseObjectValues = true)
        assertEquals(2, plain?.size)
        assertEquals(null, (plain?.first() as? JsonObject)?.get("ledger_id"))
    }

    @Test
    fun `keyed-object items report their real key so audit paths can locate them`() {
        val json = """{ "result": { "ledger": {
            "LG1": { "asset": "XXBT", "amount": "0.01" },
            "LG2": { "asset": "ZUSD", "amount": "100" }
        } } }"""
        val items = responseItemsWithKeys(json, "result.ledger", responseObjectValues = true, itemKeyField = "ledger_id")
        assertEquals(listOf("LG1", "LG2"), items?.map { (key, _) -> key })
        assertEquals(JsonPath("$.result.ledger.LG1"), keyedItemJsonPath("result.ledger", "LG1"))

        // Round-trips through the app's own path resolver, proving the audit link actually finds it.
        val root = Json.parseToJsonElement(json)
        val resolved = root.resolveJsonPathElement(keyedItemJsonPath("result.ledger", "LG1").value.removePrefix("$."))
        assertEquals("0.01", ((resolved as? JsonObject)?.get("amount") as? JsonPrimitive)?.content)
    }

    @Test
    fun `a nested items key flattens each container into its own rows, with resolvable audit paths`() {
        // Binance asset/dribblet: one conversion sweeps several small balances into BNB, and only the
        // nested detail rows carry the per-asset movement.
        val json =
            """{ "total": 1, "userAssetDribblets": [
                { "operateTime": 1615985535000, "totalTransferedAmount": "0.00132256", "transId": 45178372831,
                  "userAssetDribbletDetails": [
                    { "transId": 4359321, "fromAsset": "XRP", "amount": "0.17015309", "transferedAmount": "0.000091" },
                    { "transId": 4359322, "fromAsset": "ADA", "amount": "1.5", "transferedAmount": "0.000141" }
                  ] }
            ] }"""
        val items = responseItemsWithKeys(json, "userAssetDribblets", nestedItemsKey = "userAssetDribbletDetails")
        assertEquals(2, items?.size)
        assertEquals(
            listOf("XRP", "ADA"),
            items.orEmpty().map { (_, item) -> ((item as JsonObject)["fromAsset"] as JsonPrimitive).content },
        )

        val firstKey = items.orEmpty().first().first
        assertEquals("[0].userAssetDribbletDetails[0]", firstKey)
        val path = keyedItemJsonPath("userAssetDribblets", firstKey!!)
        assertEquals(JsonPath("$.userAssetDribblets[0].userAssetDribbletDetails[0]"), path)

        // The audit path resolves against the real response, so "view origin" lands on the mapped row.
        val resolved = Json.parseToJsonElement(json).resolveJsonPathElement(path.value.removePrefix("$."))
        assertEquals("0.17015309", ((resolved as? JsonObject)?.get("amount") as? JsonPrimitive)?.content)
    }

    @Test
    fun `a container with no nested array contributes no rows`() {
        val json = """{ "userAssetDribblets": [ { "transId": 1 } ] }"""
        assertEquals(0, responseItemsArray(json, "userAssetDribblets", nestedItemsKey = "userAssetDribbletDetails")?.size)
    }

    @Test
    fun `plain array items report no key, falling back to index paths`() {
        val json = """{ "trades": [ { "id": "T1" }, { "id": "T2" } ] }"""
        val items = responseItemsWithKeys(json, "trades")
        assertEquals(listOf<String?>(null, null), items?.map { (key, _) -> key })
    }

    @Test
    fun `minor-units integer is divided by the ISO standard divisor, not the storage scale`() {
        val gbp = Currency(id = CurrencyId(1), code = "GBP", name = "British Pound", scaleFactor = 1_000_000_000_000_000_000L)
        // 4194 pence == £41.94, regardless of GBP's much larger internal storage scale.
        val money = minorUnitsToMoney(4194L, gbp, divisorOverrides = emptyMap())
        assertEquals("41.94", money.toDisplayValue().toString())
    }

    @Test
    fun `a strategy divisor override wins over the ISO standard`() {
        val gbp = Currency(id = CurrencyId(1), code = "GBP", name = "British Pound", scaleFactor = 1_000_000_000_000_000_000L)
        val money = minorUnitsToMoney(4194L, gbp, divisorOverrides = mapOf("GBP" to 1000L))
        assertEquals("4.194", money.toDisplayValue().toString())
    }

    @Test
    fun `parses epoch and iso timestamps`() {
        assertEquals(1_600_000_000_000L, parseApiTimestamp("1600000000000", TimestampFormat.EPOCH_MS)?.toEpochMilliseconds())
        assertEquals(1_600_000_000_000L, parseApiTimestamp("1600000000", TimestampFormat.EPOCH_S)?.toEpochMilliseconds())
        assertEquals(1_600_000_000_500L, parseApiTimestamp("1600000000.5", TimestampFormat.EPOCH_S_FLOAT)?.toEpochMilliseconds())
        assertEquals(
            0L,
            parseApiTimestamp("1970-01-01T00:00:00Z", TimestampFormat.ISO_8601)?.toEpochMilliseconds(),
        )
        assertNull(parseApiTimestamp("not-a-number", TimestampFormat.EPOCH_MS))
    }
}
