package com.moneymanager.cryptodata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CryptoDatasetTest {
    @Test
    fun parsesTickerAndNameIgnoringLegacyDecimalsColumn() {
        // Older stored catalogs carried a third DECIMALS column; it is ignored.
        val map = parseCryptoDataset("BTC\tBitcoin\t8\nSOL\tSolana\t\n\n")
        assertEquals("Bitcoin", map["BTC"]?.name)
        assertEquals("Solana", map["SOL"]?.name)
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
                    CryptoDatasetEntry("BTC", "Bitcoin"),
                    CryptoDatasetEntry("SOL", "Duplicate wins-first"),
                ),
            )
        val map = parseCryptoDataset(tsv)
        assertEquals(2, map.size)
        assertEquals("Solana", map["SOL"]?.name)
        assertEquals("Bitcoin", map["BTC"]?.name)
    }
}
