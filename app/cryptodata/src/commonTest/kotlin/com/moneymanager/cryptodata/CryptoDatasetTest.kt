package com.moneymanager.cryptodata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoDatasetTest {
    @Test
    fun parsesTickerNameAndOptionalDecimals() {
        val map = parseCryptoDataset("BTC\tBitcoin\t8\nSOL\tSolana\t\n\n")
        assertEquals("Bitcoin", map["BTC"]?.name)
        assertEquals(100_000_000L, map["BTC"]?.scaleFactor)
        assertEquals("Solana", map["SOL"]?.name)
        // Blank decimals -> null scale factor ("name known, decimals unknown").
        assertNull(map["SOL"]?.scaleFactor)
    }

    @Test
    fun uppercasesSymbolsAndSkipsBlankOrInvalidRows() {
        val map = parseCryptoDataset("sol\tSolana\n\t\n\tNoSymbol\nXRP\t\n")
        assertTrue("SOL" in map)
        // A row with a blank symbol or a blank name is skipped.
        assertEquals(1, map.size)
    }

    @Test
    fun renderRoundTripsAndDeduplicatesBySymbol() {
        val tsv =
            renderCryptoDataset(
                listOf(
                    CryptoDatasetEntry("sol", "Solana"),
                    CryptoDatasetEntry("BTC", "Bitcoin", decimals = 8),
                    CryptoDatasetEntry("SOL", "Duplicate wins-first"),
                ),
            )
        val map = parseCryptoDataset(tsv)
        assertEquals(2, map.size)
        assertEquals("Solana", map["SOL"]?.name)
        assertEquals(100_000_000L, map["BTC"]?.scaleFactor)
    }
}
