package com.moneymanager.database.mapper

import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId

/**
 * Builds the right [Asset] subtype from a joined `asset_details` row. The `kind` discriminator
 * (see asset/Asset.sq) selects fiat [Currency] vs [CryptoAsset]; both carry the same id/code/name/
 * scaleFactor columns.
 */
object AssetRowMapper {
    const val KIND_CRYPTO = "CRYPTO"

    fun buildAsset(
        id: Long,
        code: String,
        name: String,
        scaleFactor: Long,
        kind: String,
    ): Asset =
        if (kind == KIND_CRYPTO) {
            CryptoAsset(id = CryptoId(id), code = code, name = name, scaleFactor = scaleFactor)
        } else {
            Currency(id = CurrencyId(id), code = code, name = name, scaleFactor = scaleFactor)
        }
}
