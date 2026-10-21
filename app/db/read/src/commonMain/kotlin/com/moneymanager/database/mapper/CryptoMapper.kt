package com.moneymanager.database.mapper

import com.moneymanager.domain.model.CryptoAsset
import tech.mappie.api.ObjectMappie
import com.moneymanager.database.sql.crypto.Crypto as DbCrypto

// Written out: Mappie (2.4.21) also matches the internal `var _hashCode` Kotlin/Wasm's Any declares when both classes
// come from other modules, and the wasm link then can't find its accessors.
object CryptoMapper : ObjectMappie<DbCrypto, CryptoAsset>(), IdConversions {
    override fun map(from: DbCrypto): CryptoAsset =
        CryptoAsset(
            id = toCryptoId(from.id),
            revisionId = from.revision_id,
            code = from.code,
            name = from.name,
            scaleFactor = from.scale_factor,
        )
}
