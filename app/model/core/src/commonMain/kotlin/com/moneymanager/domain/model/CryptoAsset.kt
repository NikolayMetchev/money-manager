package com.moneymanager.domain.model

import kotlinx.serialization.Serializable

/**
 * Represents a crypto asset (e.g. BTC, ETH, CRO, BNB) — one [Asset] class, sibling to [Currency].
 *
 * Crypto assets are not part of the platform ISO-4217 list; they are created on demand (e.g. by
 * the crypto.com importer) rather than seeded. Every crypto asset uses the fixed 18-decimal
 * [CRYPTO_SCALE_FACTOR] (the highest precision in practice — ETH/ERC-20 wei), so any observed
 * amount parses exactly regardless of which file or API introduced the ticker first; amounts are
 * stored as arbitrary-precision integers (see [Money]), so the large scale cannot overflow.
 *
 * @property id Unique identifier, drawn from the shared `asset` id space
 * @property code Ticker symbol (e.g. "BTC", "ETH")
 * @property name Human-readable name (e.g. "Bitcoin")
 * @property scaleFactor Stored amount = display amount × scaleFactor
 */
data class CryptoAsset(
    override val id: CryptoId,
    val revisionId: Long = 1,
    override val code: String,
    override val name: String,
    override val scaleFactor: Long = CRYPTO_SCALE_FACTOR,
) : Asset {
    companion object {
        /** 18 decimal places (ETH/ERC-20 wei) — the fixed precision of every crypto asset. */
        const val CRYPTO_SCALE_FACTOR = 1_000_000_000_000_000_000L
    }
}

@Serializable
@JvmInline
value class CryptoId(
    override val id: Long,
) : AssetId {
    override fun toString() = id.toString()
}
