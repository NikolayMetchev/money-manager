package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.TransferAttribute
import com.moneymanager.domain.model.TransferId
import kotlinx.coroutines.flow.Flow

interface TransferAttributeRepository {
    /**
     * Gets all current attributes for a transaction.
     * Results are ordered by attribute type name.
     */
    fun getByTransaction(transactionId: TransferId): Flow<List<TransferAttribute>>

    /**
     * Inserts a new attribute. This will trigger the attribute INSERT trigger
     * which bumps the transfer revision and records to audit.
     */
    suspend fun insert(
        transactionId: TransferId,
        attributeTypeId: AttributeTypeId,
        value: String,
    ): Long

    /**
     * Updates an attribute's value. This will trigger the attribute UPDATE trigger
     * which bumps the transfer revision and records to audit.
     */
    suspend fun updateValue(
        id: Long,
        newValue: String,
    )

    /**
     * Deletes an attribute by ID. This will trigger the attribute DELETE trigger
     * which bumps the transfer revision and records to audit.
     */
    suspend fun delete(id: Long)

    /**
     * Inserts multiple attributes in batch mode (triggers disabled).
     * Used during CSV import to avoid audit entries for initial import.
     *
     * @param transactionId The transaction to add attributes to
     * @param attributes List of (attributeTypeId, value) pairs
     */
    suspend fun insertBatch(
        transactionId: TransferId,
        attributes: List<Pair<AttributeTypeId, String>>,
    )
}
