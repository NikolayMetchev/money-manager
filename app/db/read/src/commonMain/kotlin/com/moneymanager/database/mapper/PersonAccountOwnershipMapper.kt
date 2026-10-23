package com.moneymanager.database.mapper

import com.moneymanager.database.sql.person.Person_account_ownership
import com.moneymanager.domain.model.PersonAccountOwnership
import tech.mappie.api.ObjectMappie

// Written out: Mappie (2.4.21) also matches the internal `var _hashCode` Kotlin/Wasm's Any declares when both classes
// come from other modules, and the wasm link then can't find its accessors.
object PersonAccountOwnershipMapper :
    ObjectMappie<Person_account_ownership, PersonAccountOwnership>(),
    IdConversions {
    override fun map(from: Person_account_ownership): PersonAccountOwnership =
        PersonAccountOwnership(
            id = from.id,
            revisionId = from.revision_id,
            personId = toPersonId(from.person_id),
            accountId = toAccountId(from.account_id),
        )
}
