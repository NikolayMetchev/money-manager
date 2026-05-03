package com.moneymanager.domain.repository

import com.moneymanager.domain.model.DeclinedTransaction
import kotlinx.coroutines.flow.Flow

interface DeclinedTransactionRepository {
    /**
     * Records a transaction as declined.
     *
     * @param transactionId The permanent ID of the transaction
     * @param declineReason The reason the transaction was declined
     */
    suspend fun insert(
        transactionId: Long,
        declineReason: String,
    )

    /**
     * Returns a flow of all declined transactions.
     */
    fun getAll(): Flow<List<DeclinedTransaction>>

    /**
     * Returns the declined transaction with the given ID, or null if not found.
     */
    suspend fun getById(transactionId: Long): DeclinedTransaction?

    /**
     * Returns true if the given transaction ID has been marked as declined.
     */
    suspend fun isDeclined(transactionId: Long): Boolean

    /**
     * Removes the declined record for the given transaction ID.
     */
    suspend fun delete(transactionId: Long)
}
