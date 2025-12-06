@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.mapper

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import tech.mappie.api.ObjectMappie
import kotlin.time.Instant

object AccountMapper : ObjectMappie<com.moneymanager.database.sql.Account, Account>() {
    override fun map(from: com.moneymanager.database.sql.Account) =
        mapping {
            Account::id fromValue AccountId(from.id)
            Account::openingDate fromValue Instant.fromEpochMilliseconds(from.openingDate)
        }
}
