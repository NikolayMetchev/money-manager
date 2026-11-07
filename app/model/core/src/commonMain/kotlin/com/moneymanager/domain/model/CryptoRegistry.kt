package com.moneymanager.domain.model

import kotlin.concurrent.Volatile

/**
 * A source of crypto-asset definitions (ticker → display name).
 *
 * Implemented by the bundled offline catalog (see `app/cryptodata`) and installed into
 * [CryptoRegistry] at app startup. Kept intentionally tiny and synchronous so the registry's callers
 * — a DB write transaction, the CSV applier, the create-crypto dialog — stay non-suspend.
 */
interface CryptoCatalog {
    /** Returns the known [CryptoRegistry.Entry] for [code] (already uppercased), or null. */
    fun lookup(code: String): CryptoRegistry.Entry?

    /** Every alias the catalog knows, as upper-case alias to the upper-case code of the asset it names. */
    val aliases: Map<String, String> get() = emptyMap()
}

/**
 * Registry of known crypto assets (ticker → display name).
 *
 * Crypto assets are not part of the platform ISO-4217 list, so they cannot be seeded from it. Instead
 * they are created on demand during import, using this registry to resolve a human-readable name.
 * Precision is not looked up: every crypto asset is created with the fixed 18-decimal
 * [CryptoAsset.CRYPTO_SCALE_FACTOR].
 *
 * Resolution order for a ticker is: **built-in default entries → refreshed overrides → installed
 * catalog**. The hand-curated defaults win because they carry correct names for the few coins that
 * matter, whereas the large offline catalog (installed via [install]) is a noisy ~17k-ticker dump
 * with arbitrary names for colliding tickers. A user-triggered network refresh ([installOverrides])
 * layers newer long-tail names above the shipped catalog but still below the curated defaults. The
 * defaults always resolve even before a catalog is installed (tests, early startup).
 */
object CryptoRegistry {
    /** A known crypto asset's display name. */
    data class Entry(
        val name: String,
    )

    /** Built-in fallback entries — always available even with no catalog installed. */
    private val defaultEntries =
        mapOf(
            "BTC" to Entry("Bitcoin"),
            "ETH" to Entry("Ethereum"),
            "BNB" to Entry("BNB"),
            "CRO" to Entry("Cronos"),
            "BOSON" to Entry("Boson Protocol"),
            "USDC" to Entry("USD Coin"),
            "USDT" to Entry("Tether"),
        )

    @Volatile
    private var installedCatalog: CryptoCatalog? = null

    @Volatile
    private var overrides: Map<String, Entry> = emptyMap()

    /** Installs the primary [catalog] (typically the large bundled offline dataset). */
    fun install(catalog: CryptoCatalog) {
        installedCatalog = catalog
    }

    /**
     * Layers [entries] on top of the installed catalog (e.g. a network-refreshed name list). Keys are
     * uppercased; entries win over both the installed catalog and the built-in defaults.
     */
    fun installOverrides(entries: Map<String, Entry>) {
        overrides = entries.mapKeys { it.key.uppercase() }
    }

    /** Clears installed catalog + overrides. For deterministic tests of this process-global state. */
    fun resetForTest() {
        installedCatalog = null
        overrides = emptyMap()
    }

    /** Returns the known [Entry] for [code], or null if the ticker is unknown to every layer. */
    fun lookup(code: String): Entry? {
        val upper = code.uppercase()
        return defaultEntries[upper] ?: overrides[upper] ?: installedCatalog?.lookup(upper)
    }

    /** The code of the asset the installed catalog says [code] is another code for, or null. */
    fun aliasTarget(code: String): String? = installedCatalog?.aliases?.get(code.uppercase())

    /** Every code the installed catalog says is another code for the asset [code], sorted. */
    fun catalogAliasesOf(code: String): List<String> =
        installedCatalog
            ?.aliases
            .orEmpty()
            .filterValues { it == code.uppercase() }
            .keys
            .sorted()

    /** Returns the display name for [code], defaulting to the ticker itself for unknown tickers. */
    fun nameFor(code: String): String = lookup(code)?.name ?: code.uppercase()
}
