package com.moneymanager.cryptodata

import com.moneymanager.domain.model.CryptoCatalog
import com.moneymanager.domain.model.CryptoRegistry

/**
 * The shipped offline crypto catalog, compiled in from `data/coins.tsv` at build time and parsed once on
 * first [lookup] (lazy, thread-safe). Depends on nothing at runtime, so imports resolve crypto tickers with
 * real names offline.
 */
object BundledCryptoCatalog : CryptoCatalog {
    private val entries: Map<String, CryptoRegistry.Entry> by lazy { parseCryptoDataset(GeneratedCryptoDataset.COINS_TSV) }

    override fun lookup(code: String): CryptoRegistry.Entry? = entries[code.uppercase()]

    /** Number of loaded entries (forces the lazy parse). Exposed for diagnostics/tests. */
    val size: Int get() = entries.size
}
