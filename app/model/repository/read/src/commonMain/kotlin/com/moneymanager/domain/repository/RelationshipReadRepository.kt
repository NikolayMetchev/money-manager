package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Relationship
import com.moneymanager.domain.model.TransactionId
import kotlinx.coroutines.flow.Flow

interface RelationshipReadRepository {
    /** Every relationship [transactionId] is a member of, in any role, ordered by relationship type name. */
    fun getByTransaction(transactionId: TransactionId): Flow<List<Relationship>>

    /**
     * Batch lookup of every relationship any of [transactionIds] is a member of — for callers that would
     * otherwise issue one [getByTransaction] round trip per id, e.g. the re-import planner walking the
     * conduit chains of thousands of pass-through rows. Each relationship appears once, with all its members.
     */
    suspend fun getByTransactions(transactionIds: Collection<TransactionId>): List<Relationship>
}
