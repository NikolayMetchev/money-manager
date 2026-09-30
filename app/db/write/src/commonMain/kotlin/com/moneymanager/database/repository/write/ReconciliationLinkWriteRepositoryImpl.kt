package com.moneymanager.database.repository.write

import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.repository.ReconciliationReadRepository
import com.moneymanager.domain.repository.write.ReconciliationLinkWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

class ReconciliationLinkWriteRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
    reader: ReconciliationReadRepository,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : ReconciliationLinkWriteRepository,
    ReconciliationReadRepository by reader {
    private val writeQueries = database.reconciliationLinkWriteQueries

    override suspend fun setLinks(
        shadowAccountId: AccountId,
        realAccountIds: Set<AccountId>,
    ): Unit =
        withContext(coroutineContext) {
            val now = Clock.System.now().toEpochMilliseconds()
            writeQueries.transaction {
                writeQueries.deleteLinksForShadow(shadowAccountId.id)
                realAccountIds.forEach { writeQueries.insertLink(shadowAccountId.id, it.id, now) }
            }
        }
}
