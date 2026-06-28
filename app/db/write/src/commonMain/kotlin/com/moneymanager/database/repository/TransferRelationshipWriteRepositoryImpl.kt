package com.moneymanager.database.repository

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.domain.repository.TransferRelationshipWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TransferRelationshipWriteRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
    reader: TransferRelationshipReadRepository,
) : TransferRelationshipWriteRepository,
    TransferRelationshipReadRepository by reader {
    private val writeQueries = database.transferRelationshipWriteQueries

    override suspend fun insert(
        id1: TransferId,
        id2: TransferId,
        typeId: RelationshipTypeId,
    ): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.insert(id1.id, id2.id, typeId.id)
        }

    override suspend fun delete(
        id1: TransferId,
        id2: TransferId,
        typeId: RelationshipTypeId,
    ): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.delete(id1.id, id2.id, typeId.id)
        }
}
