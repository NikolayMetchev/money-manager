package com.moneymanager.domain.model

/**
 * Represents a transaction that was declined (e.g. declined by the Monzo API).
 * Declined transactions are stored for record-keeping but excluded from all balance calculations.
 *
 * @property transactionId The permanent ID of the transaction (references the transaction_id table)
 * @property declineReason The reason the transaction was declined
 */
data class DeclinedTransaction(
    val transactionId: Long,
    val declineReason: String,
)
