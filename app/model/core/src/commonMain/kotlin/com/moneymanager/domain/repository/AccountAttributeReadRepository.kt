package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountAttribute
import com.moneymanager.domain.model.AccountId
import kotlinx.coroutines.flow.Flow

interface AccountAttributeReadRepository {
    /**
     * Gets all current attributes for an account.
     * Results are ordered by attribute type name.
     */
    fun getByAccount(accountId: AccountId): Flow<List<AccountAttribute>>
}
