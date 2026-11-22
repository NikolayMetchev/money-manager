package com.moneymanager.domain.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AssetIdSerializerTest {
    private val json = Json

    @Test
    fun `a currency id reads back as a currency id`() {
        val text = json.encodeToString(AssetIdSerializer, CurrencyId(12))
        assertEquals("\"currency:12\"", text)
        assertEquals(CurrencyId(12), json.decodeFromString(AssetIdSerializer, text))
    }

    @Test
    fun `a crypto id reads back as a crypto id`() {
        val text = json.encodeToString(AssetIdSerializer, CryptoId(34))
        assertEquals("\"crypto:34\"", text)
        assertEquals(CryptoId(34), json.decodeFromString(AssetIdSerializer, text))
    }

    @Test
    fun `the same number in two kinds stays two different ids`() {
        val currency = json.decodeFromString(AssetIdSerializer, "\"currency:5\"")
        val crypto = json.decodeFromString(AssetIdSerializer, "\"crypto:5\"")
        assertEquals(5, currency.id)
        assertEquals(5, crypto.id)
        assertEquals(false, currency == crypto)
    }

    @Test
    fun `text that is not an asset id is refused`() {
        assertFailsWith<SerializationException> { json.decodeFromString(AssetIdSerializer, "\"stock:5\"") }
        assertFailsWith<SerializationException> { json.decodeFromString(AssetIdSerializer, "\"crypto:x\"") }
    }
}
