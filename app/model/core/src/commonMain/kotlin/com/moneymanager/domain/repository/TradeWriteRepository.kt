@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import kotlin.time.Instant

interface TradeWriteRepository : TradeReadRepository {
    /**
     * Creates a cross-asset trade: [fromAmount] leaves [fromAccountId], [toAmount] enters
     * [toAccountId] (the two [Money] legs may be denominated in different assets). Allocates a
     * `transaction_id`, inserts the trade, and records provenance.
     */
    @Suppress("LongParameterList")
    suspend fun createTrade(
        timestamp: Instant,
        description: String,
        fromAccountId: AccountId,
        fromAmount: Money,
        toAccountId: AccountId,
        toAmount: Money,
        source: Source,
    ): TradeId

    suspend fun deleteTrade(id: TradeId)
}
