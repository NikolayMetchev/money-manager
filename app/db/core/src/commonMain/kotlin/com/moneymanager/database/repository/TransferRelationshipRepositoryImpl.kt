package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.RelationshipType
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.TransferRelationship
import com.moneymanager.domain.repository.TransferRelationshipRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class TransferRelationshipRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
) : TransferRelationshipRepository {
    private val queries = database.transferRelationshipQueries

    override fun getByTransfer(transferId: TransferId): Flow<List<TransferRelationship>> =
        queries
            .selectByTransfer(transferId.id, transferId.id)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    TransferRelationship(
                        id1 = TransferId(row.id1),
                        id2 = TransferId(row.id2),
                        relationshipType =
                            RelationshipType(
                                id = RelationshipTypeId(row.relationship_type_id),
                                name = row.relationship_type_name,
                            ),
                    )
                }
            }

    override suspend fun insert(
        id1: TransferId,
        id2: TransferId,
        typeId: RelationshipTypeId,
    ): Unit =
        withContext(Dispatchers.Default) {
            queries.insert(id1.id, id2.id, typeId.id)
        }

    override suspend fun delete(
        id1: TransferId,
        id2: TransferId,
        typeId: RelationshipTypeId,
    ): Unit =
        withContext(Dispatchers.Default) {
            queries.delete(id1.id, id2.id, typeId.id)
        }
}
