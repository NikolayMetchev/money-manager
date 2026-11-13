package com.moneymanager.domain.model

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssetAliasTest {
    private val catalog =
        object : CryptoCatalog {
            override fun lookup(code: String): CryptoRegistry.Entry? = if (code == "GOLD") CryptoRegistry.Entry("Gold token") else null

            override val aliases: Map<String, String> = mapOf("LUNC" to "LUNA", "ZUSD" to "USD")
        }

    @AfterTest
    fun reset() = CryptoRegistry.resetForTest()

    private fun resolve(
        code: String,
        aliases: Map<String, String> = emptyMap(),
        assets: Set<String> = emptySet(),
        codeByName: Map<String, String> = emptyMap(),
    ) = resolveAssetCode(code, aliases, codeByName) { it in assets }

    private val currencyNames = codesByName(listOf("EUR" to "Euro", "USD" to "US Dollar", "GBP" to "British Pound", "XAU" to "Gold"))

    private fun resolveName(
        text: String,
        aliases: Map<String, String> = emptyMap(),
    ) = resolve(text, aliases, assets = setOf("EUR", "USD", "GBP", "XAU"), codeByName = currencyNames)

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
    fun `a currency written as its name, then a country, resolves to its code`() {
        assertEquals("EUR", resolveName("EURO FRANCE"))
        assertEquals("USD", resolveName("US DOLLAR UNITED STATES"))
        assertEquals("USD", resolveName("US  dollar".uppercase()))
    }

    @Test
    fun `a name only an alias knows resolves through the alias`() {
        assertEquals("GBP", resolveName("POUND STERLING GREAT BRITAIN", aliases = mapOf("POUND STERLING" to "GBP")))
    }

    @Test
    fun `a code or an alias wins over a name`() {
        assertEquals("EUR", resolveName("EUR"))
        assertEquals("GBP", resolveName("EURO", aliases = mapOf("EURO" to "GBP")))
    }

    @Test
    fun `a ticker the crypto catalog knows is never read as a name`() {
        CryptoRegistry.install(catalog)
        assertEquals("GOLD", resolveName("GOLD"))
    }

    @Test
    fun `text naming no asset stays as it is`() {
        assertEquals("FRENCH FRANCS", resolveName("FRENCH FRANCS"))
    }

    @Test
    fun `catalog aliases of an asset are listed in order`() {
        CryptoRegistry.install(catalog)
        assertEquals(listOf("LUNC"), CryptoRegistry.catalogAliasesOf("LUNA"))
        assertTrue(CryptoRegistry.catalogAliasesOf("BTC").isEmpty())
    }
}
