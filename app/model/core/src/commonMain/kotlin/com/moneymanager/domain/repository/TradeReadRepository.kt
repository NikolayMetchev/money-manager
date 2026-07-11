package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import kotlinx.coroutines.flow.Flow

interface TradeReadRepository {
    fun getTradeById(id: TradeId): Flow<Trade?>

    fun getTradesByAccount(accountId: AccountId): Flow<List<Trade>>

    /** Number of trades touching [accountId] on either leg. */
    suspend fun countTradesByAccount(accountId: AccountId): Long
}
