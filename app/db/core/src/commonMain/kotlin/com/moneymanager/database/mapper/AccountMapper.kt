@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.mapper

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.Asset
import tech.mappie.api.ObjectMappie
import kotlin.time.Instant

object AccountMapper : ObjectMappie<com.moneymanager.database.sql.AccountView, Account>() {
    override fun map(from: com.moneymanager.database.sql.AccountView) =
        mapping {
            Account::openingDate fromValue Instant.fromEpochMilliseconds(from.openingDate)
            Account::asset fromValue Asset(id = from.assetId, name = from.assetName)
        }
}
