package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransferAttributeReadRepository

interface TransferAttributeWriteRepository : TransferAttributeReadRepository {
    /**
     * Inserts a new attribute. This will trigger the attribute INSERT trigger
     * which bumps the transfer revision and records to audit.
     */
    suspend fun insert(
        transactionId: TransferId,
        attributeTypeId: AttributeTypeId,
        value: String,
        groupKey: String = "",
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
}
