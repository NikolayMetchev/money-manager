package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.PassThroughAccountId
import com.moneymanager.domain.repository.PassThroughAccountReadRepository

interface PassThroughAccountWriteRepository : PassThroughAccountReadRepository {
    /** Creates a new definition; returns the generated id. */
    suspend fun create(account: PassThroughAccount): PassThroughAccountId

    /** Updates an existing definition (identified by [PassThroughAccount.id]). */
    suspend fun update(account: PassThroughAccount)

    /** Deletes a definition by id. */
    suspend fun delete(id: PassThroughAccountId)
}
