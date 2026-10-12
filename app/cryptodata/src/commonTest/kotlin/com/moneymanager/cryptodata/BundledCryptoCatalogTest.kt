package com.moneymanager.cryptodata

import com.moneymanager.domain.model.CryptoRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundledCryptoCatalogTest {
    @AfterTest
    fun tearDown() = CryptoRegistry.resetForTest()

    @Test
    fun bundledCatalogLoadsManyEntriesWithCanonicalNames() {
        assertTrue(BundledCryptoCatalog.size > 10_000, "expected a large bundled catalog, was ${BundledCryptoCatalog.size}")
        assertEquals("Solana", BundledCryptoCatalog.lookup("SOL")?.name)
        assertEquals("Solana", BundledCryptoCatalog.lookup("sol")?.name)
    }

    @Test
    fun registryResolvesFromInstalledCatalogButCuratedDefaultsWin() {
        CryptoRegistry.install(BundledCryptoCatalog)
        // Long-tail ticker resolves from the bundled catalog.
        assertEquals("Solana", CryptoRegistry.nameFor("SOL"))
        // Curated default still wins over the catalog.
        assertEquals("Bitcoin", CryptoRegistry.nameFor("BTC"))
    }

    @Test
    fun overridesWinOverInstalledCatalog() {
        CryptoRegistry.install(BundledCryptoCatalog)
        CryptoRegistry.installOverrides(mapOf("SOL" to CryptoRegistry.Entry("Solana (refreshed)")))
        assertEquals("Solana (refreshed)", CryptoRegistry.nameFor("SOL"))
    }
}
