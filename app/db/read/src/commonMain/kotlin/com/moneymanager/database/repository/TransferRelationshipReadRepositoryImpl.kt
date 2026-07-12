package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.RelationshipType
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.TransferRelationship
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class TransferRelationshipReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : TransferRelationshipReadRepository {
    private val selectQueries = database.transferRelationshipSelectQueries

    override fun getByTransfer(transferId: TransferId): Flow<List<TransferRelationship>> =
        selectQueries
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

    override suspend fun getByTransfers(transferIds: Collection<TransferId>): List<TransferRelationship> =
        withContext(Dispatchers.Default) {
            transferIds
                .asSequence()
                .map { it.id }
                .distinct()
                .chunked(MAX_IDS_PER_QUERY)
                .flatMap { chunk ->
                    selectQueries.selectByTransfers(chunk).executeAsList()
                }
                // A relationship whose two sides fall into different chunks is returned by both.
                .distinct()
                .map { row ->
                    TransferRelationship(
                        id1 = TransferId(row.id1),
                        id2 = TransferId(row.id2),
                        relationshipType =
                            RelationshipType(
                                id = RelationshipTypeId(row.relationship_type_id),
                                name = row.relationship_type_name,
                            ),
                    )
                }.toList()
        }
}
