package com.moneymanager.domain.model

/**
 * Built-in registry of known crypto assets (ticker → display name + scale factor).
 *
 * Crypto assets are not part of the platform ISO-4217 list, so they cannot be seeded from it.
 * Instead they are created on demand (e.g. by the crypto.com importer) using this registry to
 * resolve a human-readable name and the asset's precision. Unknown tickers fall back to the
 * default 8-decimal scale ([CryptoAsset.DEFAULT_CRYPTO_SCALE_FACTOR]).
 *
 * Scale factors are [Long] (not [Int]) because high-precision tokens such as ETH use 18 decimal
 * places (scale factor 1e18), which overflows an [Int].
 */
object CryptoRegistry {
    /** A known crypto asset's display name and scale factor. */
    data class Entry(
        val name: String,
        val scaleFactor: Long,
    )

    private const val SCALE_8 = 100_000_000L // 8 decimals (satoshi-style)
    private const val SCALE_18 = 1_000_000_000_000_000_000L // 18 decimals (ETH/ERC-20 wei)

    private val entries =
        mapOf(
            "BTC" to Entry("Bitcoin", SCALE_8),
            "ETH" to Entry("Ethereum", SCALE_18),
            "BNB" to Entry("BNB", SCALE_8),
            "CRO" to Entry("Cronos", SCALE_8),
            "BOSON" to Entry("Boson Protocol", SCALE_8),
            "USDC" to Entry("USD Coin", SCALE_8),
            "USDT" to Entry("Tether", SCALE_8),
        )

    /** Returns the known [Entry] for [code], or null if the ticker is unknown. */
    fun lookup(code: String): Entry? = entries[code.uppercase()]

    /** Returns the scale factor for [code], defaulting to 8 decimals for unknown tickers. */
    fun scaleFactorFor(code: String): Long = lookup(code)?.scaleFactor ?: CryptoAsset.DEFAULT_CRYPTO_SCALE_FACTOR

    /** Returns the display name for [code], defaulting to the ticker itself for unknown tickers. */
    fun nameFor(code: String): String = lookup(code)?.name ?: code.uppercase()
}
