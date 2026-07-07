package com.moneymanager.domain.model

import kotlinx.serialization.Serializable

/**
 * Represents a crypto asset (e.g. BTC, ETH, CRO, BNB) — one [Asset] class, sibling to [Currency].
 *
 * Crypto assets are not part of the platform ISO-4217 list; they are created on demand (e.g. by
 * the crypto.com importer) rather than seeded. They use a larger [scaleFactor] than fiat to carry
 * their higher precision; amounts are stored as arbitrary-precision integers (see [Money]).
 *
 * @property id Unique identifier, drawn from the shared `asset` id space
 * @property code Ticker symbol (e.g. "BTC", "ETH")
 * @property name Human-readable name (e.g. "Bitcoin")
 * @property scaleFactor Stored amount = display amount × scaleFactor (e.g. 1e8 for 8 decimals)
 */
data class CryptoAsset(
    override val id: CryptoId,
    val revisionId: Long = 1,
    override val code: String,
    override val name: String,
    override val scaleFactor: Long = DEFAULT_CRYPTO_SCALE_FACTOR,
) : Asset {
    companion object {
        /** 8 decimal places (satoshi-style) — the default when a crypto's precision is unknown. */
        const val DEFAULT_CRYPTO_SCALE_FACTOR = 100_000_000L
    }
}

@Serializable
@JvmInline
value class CryptoId(
    override val id: Long,
) : AssetId {
    override fun toString() = id.toString()
}
