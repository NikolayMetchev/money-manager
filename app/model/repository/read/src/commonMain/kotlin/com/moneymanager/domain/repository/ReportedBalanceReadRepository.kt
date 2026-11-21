package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.reconciliation.ReportedBalance
import com.moneymanager.domain.model.reconciliation.ReportedBalanceId
import com.moneymanager.domain.model.reconciliation.ReportedBalanceSeries
import kotlinx.coroutines.flow.Flow

/** Read side of the balances statements report (#1531). */
interface ReportedBalanceReadRepository {
    /** Every account and asset some statement reports balances for, by account name then asset code. */
    fun getSeries(): Flow<List<ReportedBalanceSeries>>

    /** [accountId]'s reported balances of [asset], oldest first, rows of one instant in their statement's order. */
    suspend fun getBalances(
        accountId: AccountId,
        asset: Asset,
    ): List<ReportedBalance>

    /**
     * Where statements said balance [id], newest revision first: its CSV row, or its API request and JSON path, with
     * the record's statement account.
     */
    suspend fun getSources(id: ReportedBalanceId): List<SourceRecord>
}
