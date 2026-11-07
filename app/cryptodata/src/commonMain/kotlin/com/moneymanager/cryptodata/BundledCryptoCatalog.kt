package com.moneymanager.cryptodata

import com.moneymanager.domain.model.CryptoCatalog
import com.moneymanager.domain.model.CryptoRegistry

/**
 * The shipped offline crypto catalog, compiled in from `data/coins.tsv` at build time and parsed once on
 * first [lookup] (lazy, thread-safe). Depends on nothing at runtime, so imports resolve crypto tickers with
 * real names offline.
 */
object BundledCryptoCatalog : CryptoCatalog {
    /** Every row of the dataset, an alias's among them. The strategy catalog publishes the list from these. */
    val rows: List<CryptoDatasetEntry> by lazy { parseCryptoDatasetEntries(GeneratedCryptoDataset.COINS_TSV) }
    private val entries: Map<String, CryptoRegistry.Entry> by lazy { rows.associate { it.symbol to CryptoRegistry.Entry(it.name) } }

    override val aliases: Map<String, String> by lazy {
        rows.mapNotNull { row -> row.aliasOf?.let { row.symbol to it } }.toMap()
    }

    override fun lookup(code: String): CryptoRegistry.Entry? = entries[code.uppercase()]

    /** Number of loaded entries (forces the lazy parse). Exposed for diagnostics/tests. */
    val size: Int get() = entries.size
}
