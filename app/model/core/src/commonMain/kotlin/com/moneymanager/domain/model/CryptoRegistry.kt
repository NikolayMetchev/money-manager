package com.moneymanager.domain.model

/**
 * A source of crypto-asset definitions (ticker → display name + optional scale factor).
 *
 * Implemented by the bundled offline catalog (see `app/cryptodata`) and installed into
 * [CryptoRegistry] at app startup. Kept intentionally tiny and synchronous so the registry's callers
 * — a DB write transaction, the CSV applier, the create-crypto dialog — stay non-suspend.
 */
interface CryptoCatalog {
    /** Returns the known [CryptoRegistry.Entry] for [code] (already uppercased), or null. */
    fun lookup(code: String): CryptoRegistry.Entry?
}

/**
 * Registry of known crypto assets (ticker → display name + scale factor).
 *
 * Crypto assets are not part of the platform ISO-4217 list, so they cannot be seeded from it. Instead
 * they are created on demand during import, using this registry to resolve a human-readable name and,
 * when known, the asset's precision.
 *
 * Resolution order for a ticker is: **built-in default entries → refreshed overrides → installed
 * catalog**. The hand-curated defaults win because they carry correct names *and* decimals for the
 * few coins that matter, whereas the large offline catalog (installed via [install]) is a noisy
 * ~17k-ticker dump with no decimals and arbitrary names for colliding tickers. A user-triggered
 * network refresh ([installOverrides]) layers newer long-tail names above the shipped catalog but
 * still below the curated defaults. The defaults always resolve even before a catalog is installed
 * (tests, early startup).
 *
 * An [Entry.scaleFactor] of `null` means "name known, decimals unknown" — the bundled catalog knows
 * names but not decimals, so callers must derive the precision from the data (observed CSV precision)
 * rather than assuming a fixed scale. [scaleFactorFor] falls back to the 8-decimal default for such
 * entries and for unknown tickers.
 *
 * Scale factors are [Long] (not [Int]) because high-precision tokens such as ETH use 18 decimal
 * places (scale factor 1e18), which overflows an [Int].
 */
object CryptoRegistry {
    /** A known crypto asset's display name and (optionally known) scale factor. */
    data class Entry(
        val name: String,
        val scaleFactor: Long?,
    )

    private const val SCALE_8 = 100_000_000L // 8 decimals (satoshi-style)
    private const val SCALE_18 = 1_000_000_000_000_000_000L // 18 decimals (ETH/ERC-20 wei)

    /** Built-in fallback entries — always available even with no catalog installed. */
    private val defaultEntries =
        mapOf(
            "BTC" to Entry("Bitcoin", SCALE_8),
            "ETH" to Entry("Ethereum", SCALE_18),
            "BNB" to Entry("BNB", SCALE_8),
            "CRO" to Entry("Cronos", SCALE_8),
            "BOSON" to Entry("Boson Protocol", SCALE_8),
            "USDC" to Entry("USD Coin", SCALE_8),
            "USDT" to Entry("Tether", SCALE_8),
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

    /** Returns the scale factor for [code], defaulting to 8 decimals for unknown/decimal-less tickers. */
    fun scaleFactorFor(code: String): Long = lookup(code)?.scaleFactor ?: CryptoAsset.DEFAULT_CRYPTO_SCALE_FACTOR

    /**
     * Returns the *explicitly known* decimal count for [code], or null when the ticker is unknown or its
     * entry carries no scale factor. Callers use this to prefer a known precision while still deriving an
     * unknown asset's scale from the data — never forcing the 8-decimal default onto a name-only entry.
     */
    fun explicitDecimalsFor(code: String): Int? = lookup(code)?.scaleFactor?.let(::decimalsForScaleFactor)

    /** Returns the display name for [code], defaulting to the ticker itself for unknown tickers. */
    fun nameFor(code: String): String = lookup(code)?.name ?: code.uppercase()

    /** Number of decimal places encoded by a power-of-ten [scaleFactor] (e.g. 1e8 → 8). */
    private fun decimalsForScaleFactor(scaleFactor: Long): Int {
        var decimals = 0
        var value = scaleFactor
        while (value > 1) {
            value /= 10
            decimals++
        }
        return decimals
    }
}
