package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.CryptoReadRepository

/** An asset for [CryptoWriteRepository.upsertCryptosByCode]; see [CryptoWriteRepository.upsertCryptoByCode]. */
data class NewCryptoAsset(
    val code: String,
    val name: String?,
    val source: Source,
)

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

    /**
     * [upsertCryptoByCode] for many assets in one transaction, so a long list (the catalog's holds thousands) commits
     * once rather than syncing the file per asset.
     *
     * @return Each asset's id, in the order given
     */
    suspend fun upsertCryptosByCode(assets: List<NewCryptoAsset>): List<CryptoId>

    suspend fun updateCryptoAsset(
        crypto: CryptoAsset,
        source: Source,
    )

    suspend fun deleteCryptoAsset(id: CryptoId)

    /**
     * Makes [alias] another code for the asset with code [assetCode] (a currency when a currency and a crypto asset
     * share it), replacing what it named before. Both are read in upper case. Fails on a blank alias, an alias of
     * itself or an unknown asset. An alias may be another asset's code: that is how two assets are merged.
     */
    suspend fun setAssetAlias(
        alias: String,
        assetCode: String,
        published: Boolean = false,
    )

    suspend fun removeAssetAlias(alias: String)
}
