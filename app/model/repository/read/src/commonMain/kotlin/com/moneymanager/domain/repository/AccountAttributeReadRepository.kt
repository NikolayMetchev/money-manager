package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountAttribute
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import kotlinx.coroutines.flow.Flow

interface AccountAttributeReadRepository {
    /**
     * Gets all current attributes for an account.
     * Results are ordered by attribute type name.
     */
    fun getByAccount(accountId: AccountId): Flow<List<AccountAttribute>>

    /**
     * Gets every account's attribute of the given type (e.g. `card-last4`), ordered by account id.
     * Used to build a value → account index without loading every account's full attribute set.
     */
    fun getByType(typeId: AttributeTypeId): Flow<List<AccountAttribute>>

    /**
     * Gets every account attribute across all types, ordered by attribute type name then account id.
     * Used to build the attribute-account matcher registry (keyed by type name) for strategies whose
     * funding match or `AttributeMatchAccountMapping` may reference any attribute type.
     */
    fun getAll(): Flow<List<AccountAttribute>>
}
