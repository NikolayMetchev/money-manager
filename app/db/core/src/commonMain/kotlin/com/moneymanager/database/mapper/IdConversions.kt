@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.TransferId
import kotlin.uuid.Uuid

interface IdConversions {
    fun toAccountId(id: Long): AccountId = AccountId(id)

    fun toCurrencyId(id: String): CurrencyId = CurrencyId(Uuid.parse(id))

    fun toTransferId(id: String): TransferId = TransferId(Uuid.parse(id))
}
