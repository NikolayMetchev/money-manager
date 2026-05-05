package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountAttribute
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import kotlinx.coroutines.flow.Flow

interface AccountAttributeRepository {
    /**
     * Gets all current attributes for an account.
     * Results are ordered by attribute type name.
     */
    fun getByAccount(accountId: AccountId): Flow<List<AccountAttribute>>

    /**
     * Inserts a new attribute. This will trigger the attribute INSERT trigger
     * which bumps the account revision and records to audit.
     */
    suspend fun insert(
        accountId: AccountId,
        attributeTypeId: AttributeTypeId,
        value: String,
    ): Long

    /**
     * Updates an attribute's value. This will trigger the attribute UPDATE trigger
     * which bumps the account revision and records to audit.
     */
    suspend fun updateValue(
        id: Long,
        newValue: String,
    )

    /**
     * Deletes an attribute by ID. This will trigger the attribute DELETE trigger
     * which bumps the account revision and records to audit.
     */
    suspend fun delete(id: Long)
}
