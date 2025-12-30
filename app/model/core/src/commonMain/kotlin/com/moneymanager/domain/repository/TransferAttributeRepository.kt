package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.TransferAttribute
import com.moneymanager.domain.model.TransferId
import kotlinx.coroutines.flow.Flow

interface TransferAttributeRepository {
    /**
     * Gets all attributes for a specific transaction and revision.
     */
    fun getByTransactionAndRevision(
        transactionId: TransferId,
        revisionId: Long,
    ): Flow<List<TransferAttribute>>

    /**
     * Gets all attributes for a transaction across all revisions.
     * Results are ordered by revision descending, then attribute type name.
     */
    fun getAllByTransaction(transactionId: TransferId): Flow<List<TransferAttribute>>

    /**
     * Inserts a new attribute. This will trigger the attribute INSERT trigger
     * which bumps the transfer revision and copies other attributes.
     */
    suspend fun insert(
        transactionId: TransferId,
        revisionId: Long,
        attributeTypeId: AttributeTypeId,
        value: String,
    ): Long

    /**
     * Updates an attribute's value. This will trigger the attribute UPDATE trigger
     * which bumps the transfer revision and copies all attributes.
     */
    suspend fun updateValue(
        id: Long,
        newValue: String,
    )

    /**
     * Deletes an attribute by ID. This will trigger the attribute DELETE trigger
     * which bumps the transfer revision and copies remaining attributes.
     */
    suspend fun delete(id: Long)

    /**
     * Inserts multiple attributes in batch mode (triggers disabled).
     * Used during CSV import to avoid cascading revision bumps.
     *
     * @param transactionId The transaction to add attributes to
     * @param revisionId The revision for all attributes
     * @param attributes List of (attributeTypeId, value) pairs
     */
    suspend fun insertBatch(
        transactionId: TransferId,
        revisionId: Long,
        attributes: List<Pair<AttributeTypeId, String>>,
    )
}
