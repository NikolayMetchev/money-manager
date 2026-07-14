@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.repository.TradeReadRepository
import kotlin.time.Instant

/**
 * Outcome of [TradeWriteRepository.createTrade]: the trade's id plus whether a new row was inserted.
 * [created] is false when an identical trade already existed (the idempotent re-import/cross-source
 * path), letting callers surface the row as a duplicate instead of a fresh import.
 */
data class TradeCreateResult(
    val id: TradeId,
    val created: Boolean,
)

interface TradeWriteRepository : TradeReadRepository {
    /**
     * Creates a cross-asset trade: [fromAmount] leaves [fromAccountId], [toAmount] enters
     * [toAccountId] (the two [Money] legs may be denominated in different assets). Allocates a
     * `transaction_id`, inserts the trade, and records provenance. Idempotent: if an identical
     * trade already exists it is returned with [TradeCreateResult.created] = false.
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
    ): TradeCreateResult

    suspend fun deleteTrade(id: TradeId)
}
