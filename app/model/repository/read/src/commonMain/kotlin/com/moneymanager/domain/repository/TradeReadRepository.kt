package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

interface TradeReadRepository {
    fun getTradeById(id: TradeId): Flow<Trade?>

    fun getTradesByAccount(accountId: AccountId): Flow<List<Trade>>

    /** Number of trades touching [accountId] on either leg. */
    suspend fun countTradesByAccount(accountId: AccountId): Long

    /** Which of [accountIds] appear on either leg of any trade (batch emptiness check). */
    suspend fun accountsWithTrades(accountIds: Collection<AccountId>): Set<AccountId>

    /**
     * Trades touching any of [accountIds] whose timestamp falls in `[minTimestamp, maxTimestamp]`.
     * Loaded in one window per import so a fuzzy trade reconcile can match in memory: an export that
     * stamps to the second, or aggregates the fills another source reports individually, cannot be
     * matched by the exact-tuple lookup the writer uses.
     */
    suspend fun getTradesByAccountsAndDateRange(
        accountIds: Collection<AccountId>,
        minTimestamp: Instant,
        maxTimestamp: Instant,
    ): List<Trade>
}
