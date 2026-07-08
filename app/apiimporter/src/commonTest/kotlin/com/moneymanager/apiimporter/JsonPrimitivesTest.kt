package com.moneymanager.apiimporter

import com.moneymanager.domain.model.apistrategy.TimestampFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonPrimitivesTest {
    private fun path(
        json: String,
        p: String,
    ): String? = (Json.parseToJsonElement(json).resolveJsonPathElement(p) as? JsonPrimitive)?.contentOrNull

    @Test
    fun `resolves nested object and array paths`() {
        val json = """{ "result": { "data": [ {"price": "100"}, {"price": "200"} ] }, "code": 0 }"""
        assertEquals("100", path(json, "result.data[0].price"))
        assertEquals("200", path(json, "result.data[1].price"))
        assertEquals("0", path(json, "code"))
        assertNull(path(json, "result.data[2].price"))
        assertNull(path(json, "result.missing"))
    }

    @Test
    fun `success code check honors envelope status`() {
        val ok = """{ "code": 0, "result": {} }"""
        val bad = """{ "code": 40004, "message": "bad" }"""
        assertEquals(true, responseCodeOk(ok, "code", "0"))
        assertEquals(false, responseCodeOk(bad, "code", "0"))
        // No configured field: never gates (bank APIs rely on HTTP status).
        assertEquals(true, responseCodeOk(bad, null, null))
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
