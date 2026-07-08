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
        // Curated default still wins over the catalog and keeps its explicit precision.
        assertEquals("Bitcoin", CryptoRegistry.nameFor("BTC"))
        assertEquals(8, CryptoRegistry.explicitDecimalsFor("BTC"))
        // A name-only catalog entry has no explicit precision.
        assertEquals(null, CryptoRegistry.explicitDecimalsFor("SOL"))
    }

    @Test
    fun overridesWinOverInstalledCatalog() {
        CryptoRegistry.install(BundledCryptoCatalog)
        CryptoRegistry.installOverrides(mapOf("SOL" to CryptoRegistry.Entry("Solana (refreshed)", null)))
        assertEquals("Solana (refreshed)", CryptoRegistry.nameFor("SOL"))
    }
}
