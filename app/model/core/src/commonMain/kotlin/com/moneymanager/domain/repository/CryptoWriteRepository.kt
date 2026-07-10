package com.moneymanager.domain.repository

import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.Source

interface CryptoWriteRepository : CryptoReadRepository {
    /**
     * Creates a crypto asset, or returns the existing one if a crypto asset with the same code
     * exists. Every crypto asset is created with the fixed 18-decimal
     * [CryptoAsset.CRYPTO_SCALE_FACTOR]; the display name (when [name] is null) is resolved from
     * [com.moneymanager.domain.model.CryptoRegistry].
     *
     * @param code The ticker symbol (e.g. "BTC", "ETH")
     * @param name Optional display name; defaults to the registry name (or the ticker) when null
     * @return The id of the created or existing crypto asset
     */
    suspend fun upsertCryptoByCode(
        code: String,
        name: String? = null,
        source: Source,
    ): CryptoId

    suspend fun updateCryptoAsset(
        crypto: CryptoAsset,
        source: Source,
    )

    suspend fun deleteCryptoAsset(id: CryptoId)
}
