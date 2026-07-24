package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.repository.AccountAttributeReadRepository

interface AccountAttributeWriteRepository : AccountAttributeReadRepository {
    /**
     * Inserts a new attribute. This will trigger the attribute INSERT trigger
     * which bumps the account revision and records to audit.
     */
    suspend fun insert(
        accountId: AccountId,
        attributeTypeId: AttributeTypeId,
        value: String,
        groupKey: String = "",
    ): Long

    /**
     * Inserts a new attribute in creation mode: the attribute is recorded in the audit log
     * at the current revision without bumping the revision. Use this when the attribute is
     * part of the initial creation of the account (e.g. counterparty.id on import).
     */
    suspend fun insertInCreationMode(
        accountId: AccountId,
        attributeTypeId: AttributeTypeId,
        value: String,
        groupKey: String = "",
    ): Long

    /**
     * Writes one attribute into one (type, group) slot, inserting or updating as needed and doing nothing
     * when the value already matches. Unlike [insertInCreationMode] this never throws on a slot that is
     * already occupied, so a re-import of an already-known bank identity is a true no-op rather than a
     * swallowed UNIQUE violation.
     */
    suspend fun upsertInCreationMode(
        accountId: AccountId,
        attributeTypeId: AttributeTypeId,
        value: String,
        groupKey: String = "",
    )

    /**
     * Inserts many attributes in creation mode.
     * Default implementation preserves behavior by delegating one-by-one.
     */
    suspend fun insertInCreationModeBatch(attributes: List<AccountAttributeCreateInput>) {
        attributes.forEach { input ->
            insertInCreationMode(
                accountId = input.accountId,
                attributeTypeId = input.attributeTypeId,
                value = input.value,
                groupKey = input.groupKey,
            )
        }
    }

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

data class AccountAttributeCreateInput(
    val accountId: AccountId,
    val attributeTypeId: AttributeTypeId,
    val value: String,
    val groupKey: String = "",
)
