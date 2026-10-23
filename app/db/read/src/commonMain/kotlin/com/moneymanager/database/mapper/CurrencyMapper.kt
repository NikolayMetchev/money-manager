package com.moneymanager.database.mapper

import com.moneymanager.domain.model.Currency
import tech.mappie.api.ObjectMappie
import com.moneymanager.database.sql.currency.Currency as DbCurrency

// Written out: Mappie (2.4.21) also matches the internal `var _hashCode` Kotlin/Wasm's Any declares when both classes
// come from other modules, and the wasm link then can't find its accessors.
object CurrencyMapper : ObjectMappie<DbCurrency, Currency>(), IdConversions {
    override fun map(from: DbCurrency): Currency =
        Currency(
            id = toCurrencyId(from.id),
            revisionId = from.revision_id,
            code = from.code,
            name = from.name,
            scaleFactor = from.scale_factor,
        )
}
