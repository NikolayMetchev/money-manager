package com.moneymanager.tools.cryptodataset

import com.moneymanager.cryptodata.parseCryptoDataset
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Guards the checked-in bundled catalog: it must exist, be large, and parse with canonical popular names. */
class CryptoDatasetToolTest {
    private val coinsTsv =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { it.resolve("app/cryptodata/src/commonResources/crypto/coins.tsv") }
            .firstOrNull { it.isFile }
            ?: error("coins.tsv not found relative to the module directory")

    @Test
    fun bundledArtifactIsWellFormedAndLarge() {
        val map = parseCryptoDataset(coinsTsv.readText())
        assertTrue(map.size > 10_000, "expected a large catalog, was ${map.size}")
        assertEquals("Solana", map["SOL"]?.name)
        assertEquals("Bitcoin", map["BTC"]?.name)
        assertEquals("XRP", map["XRP"]?.name)
    }
}
