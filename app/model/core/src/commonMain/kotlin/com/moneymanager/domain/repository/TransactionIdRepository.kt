@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.TransferId

/**
 * Repository for managing permanent transaction IDs.
 * Transaction IDs are never deleted - they persist even after the transfer is deleted.
 */
interface TransactionIdRepository {
    /**
     * Creates a new permanent transaction ID record.
     * Must be called before inserting a Transfer with this ID.
     *
     * @param id The transaction ID to create
     */
    suspend fun create(id: TransferId)

    /**
     * Checks if a transaction ID exists.
     *
     * @param id The transaction ID to check
     * @return True if the ID exists
     */
    suspend fun exists(id: TransferId): Boolean
}
