package com.moneymanager.database.mapper

import com.moneymanager.domain.model.Account
import tech.mappie.api.ObjectMappie

// Written out: Mappie (2.4.21) also matches the internal `var _hashCode` Kotlin/Wasm's Any declares when both classes
// come from other modules, and the wasm link then can't find its accessors.
object AccountMapper :
    ObjectMappie<com.moneymanager.database.sql.account.Account, Account>(),
    IdConversions,
    InstantConversions {
    override fun map(from: com.moneymanager.database.sql.account.Account): Account =
        Account(
            id = toAccountId(from.id),
            revisionId = from.revision_id,
            name = from.name,
            openingDate = toInstant(from.opening_date),
            categoryId = from.category_id,
        )
}
