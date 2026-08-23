package com.moneymanager.database.repository

import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransferSourceReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only implementation of TransferSourceReadRepository using SQLDelight.
 * Transfers are stored in the unified entity_source store as entity_type_id = 7 (TRANSFER),
 * keyed by the transfer id, so this is [EntitySourceReader] pinned to [EntityType.TRANSFER].
 */
class TransferSourceReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : TransferSourceReadRepository {
    private val sources = EntitySourceReader(database)

    override suspend fun getSourcesForTransaction(transactionId: TransferId): List<SourceRecord> =
        withContext(Dispatchers.Default) {
            sources.sourcesFor(EntityType.TRANSFER, transactionId.id)
        }

    override suspend fun getSourceByRevision(
        transactionId: TransferId,
        revisionId: Long,
    ): SourceRecord? =
        withContext(Dispatchers.Default) {
            sources
                .sourcesFor(EntityType.TRANSFER, transactionId.id)
                .firstOrNull { it.revisionId == revisionId }
        }
}
