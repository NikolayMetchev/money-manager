package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.repository.ReconciliationReadRepository

interface ReconciliationLinkWriteRepository : ReconciliationReadRepository {
    /** Replaces every link of [shadowAccountId] with links to [realAccountIds] (empty = unlink all). */
    suspend fun setLinks(
        shadowAccountId: AccountId,
        realAccountIds: Set<AccountId>,
    )
}
