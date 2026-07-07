package com.moneymanager.database.mapper

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferId

interface IdConversions {
    fun toAccountId(id: Long): AccountId = AccountId(id)

    fun toCurrencyId(id: Long): CurrencyId = CurrencyId(id)

    fun toCryptoId(id: Long): CryptoId = CryptoId(id)

    fun toPersonId(id: Long): PersonId = PersonId(id)

    fun toTransferId(id: Long): TransferId = TransferId(id)

    fun toTradeId(id: Long): TradeId = TradeId(id)
}
