package com.moneymanager.domain.model

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AssetAliasTest {
    private val catalog =
        object : CryptoCatalog {
            override fun lookup(code: String): CryptoRegistry.Entry? = null

            override val aliases: Map<String, String> = mapOf("LUNC" to "LUNA", "ZUSD" to "USD")
        }

    @AfterTest
    fun reset() = CryptoRegistry.resetForTest()

    private fun resolve(
        code: String,
        aliases: Map<String, String> = emptyMap(),
        assets: Set<String> = emptySet(),
    ) = resolveAssetCode(code, aliases) { it in assets }

    @Test
    fun `a database alias wins even over an asset with that code`() {
        assertEquals("LUNA", resolve("LUNC", aliases = mapOf("LUNC" to "LUNA"), assets = setOf("LUNC", "LUNA")))
    }

    @Test
    fun `a catalog alias applies when no asset has the code`() {
        CryptoRegistry.install(catalog)
        assertEquals("USD", resolve("ZUSD"))
    }

    @Test
    fun `an asset keeps its code over a catalog alias`() {
        CryptoRegistry.install(catalog)
        assertEquals("LUNC", resolve("LUNC", assets = setOf("LUNC", "LUNA")))
    }

    @Test
    fun `an unknown code stays as it is`() {
        CryptoRegistry.install(catalog)
        assertEquals("BTC", resolve("BTC"))
    }

    @Test
    fun `catalog aliases of an asset are listed in order`() {
        CryptoRegistry.install(catalog)
        assertEquals(listOf("LUNC"), CryptoRegistry.catalogAliasesOf("LUNA"))
        assertEquals(emptyList(), CryptoRegistry.catalogAliasesOf("BTC"))
    }
}
