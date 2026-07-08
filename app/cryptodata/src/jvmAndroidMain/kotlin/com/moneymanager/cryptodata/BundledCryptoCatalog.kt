package com.moneymanager.cryptodata

import com.moneymanager.domain.model.CryptoCatalog
import com.moneymanager.domain.model.CryptoRegistry

/** Classpath location of the bundled TSV catalog (wired onto each platform's runtime resources). */
private const val BUNDLED_RESOURCE = "/crypto/coins.tsv"

/**
 * The shipped offline crypto catalog, loaded from the bundled `coins.tsv` classpath resource and parsed
 * once on first [lookup] (lazy, thread-safe). Depends on nothing at runtime, so imports resolve crypto
 * tickers with real names offline.
 *
 * If the resource can't be read (e.g. an Android packaging quirk where library Java resources aren't on
 * the classpath), the map is empty and [lookup] returns null — [CryptoRegistry] then falls back to its
 * curated defaults and the importer still auto-creates the ticker (named after itself). So a missing
 * resource degrades name quality, never breaks import.
 */
object BundledCryptoCatalog : CryptoCatalog {
    private val entries: Map<String, CryptoRegistry.Entry> by lazy {
        readBundledCryptoDataset()?.let(::parseCryptoDataset) ?: emptyMap()
    }

    override fun lookup(code: String): CryptoRegistry.Entry? = entries[code.uppercase()]

    /** Number of loaded entries (forces the lazy parse). Exposed for diagnostics/tests. */
    val size: Int get() = entries.size
}

/** Reads the bundled TSV catalog text from the classpath, or null if it isn't present/readable. */
internal fun readBundledCryptoDataset(): String? =
    BundledCryptoCatalog::class.java
        .getResourceAsStream(BUNDLED_RESOURCE)
        ?.bufferedReader()
        ?.use { it.readText() }
