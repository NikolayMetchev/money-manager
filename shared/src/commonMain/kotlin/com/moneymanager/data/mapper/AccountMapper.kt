package com.moneymanager.data.mapper

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountType
import kotlinx.datetime.Instant
import tech.mappie.api.ObjectMappie

object AccountMapper : ObjectMappie<com.moneymanager.database.Account, Account>() {
    override fun map(from: com.moneymanager.database.Account) = mapping {
        Account::type fromValue AccountType.valueOf(from.type)
        Account::isActive fromValue (from.isActive == 1L)
        Account::createdAt fromValue Instant.fromEpochMilliseconds(from.createdAt)
        Account::updatedAt fromValue Instant.fromEpochMilliseconds(from.updatedAt)
    }
}
