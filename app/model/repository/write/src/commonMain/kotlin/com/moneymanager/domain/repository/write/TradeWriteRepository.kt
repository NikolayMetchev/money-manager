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
     * `transaction_id`, inserts the trade, and records provenance.
     *
     * Idempotent as a **multiset**, not a set: [occurrence] (0-based) is the caller's count of how
     * many earlier trades in this same create pass already matched this exact field tuple (e.g. an
     * exchange order split into several byte-identical fills). The occurrence-th existing identical
     * trade is reused ([TradeCreateResult.created] = false); once existing matches are exhausted, a
     * new row is inserted. This lets N genuinely-repeated identical fills book as N distinct trades
     * while re-importing the same N fills still dedupes to the same N rows instead of only one.
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
        occurrence: Int = 0,
    ): TradeCreateResult

    suspend fun deleteTrade(id: TradeId)
}
