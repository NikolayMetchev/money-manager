package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.domain.model.reconciliation.ReconciliationLink
import com.moneymanager.domain.model.reconciliation.ReconciliationSource
import com.moneymanager.domain.model.reconciliation.ShadowAccount
import kotlinx.coroutines.flow.Flow

/** Read side of reconciliation: sources, their shadow accounts, the shadow→real links, and legs. */
interface ReconciliationReadRepository {
    /** Every source named by a CSV strategy's `ReconciliationConfig`, sorted by name. */
    fun getSources(): Flow<List<ReconciliationSource>>

    /** Every shadow account (of any source), sorted by name. */
    fun getShadowAccounts(): Flow<List<ShadowAccount>>

    /** Every shadow→real link. */
    fun getLinks(): Flow<List<ReconciliationLink>>

    /** All transfer and trade legs on [accountIds], oldest first. */
    suspend fun getLegs(accountIds: Collection<AccountId>): List<ReconciliationLeg>
}
