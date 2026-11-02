package com.moneymanager.database.repository

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.Relationship
import com.moneymanager.domain.model.RelationshipId
import com.moneymanager.domain.model.RelationshipMember
import com.moneymanager.domain.model.RelationshipRole
import com.moneymanager.domain.model.RelationshipType
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.RelationshipReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RelationshipReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : RelationshipReadRepository {
    private val selectQueries = database.relationshipSelectQueries

    override fun getByTransaction(transactionId: TransactionId): Flow<List<Relationship>> =
        selectQueries
            .selectByTransaction(transactionId.id) { id, typeId, typeName, memberId, role, kind ->
                MemberRow(id, typeId, typeName, memberId, role, kind)
            }.asFlow()
            .mapToList(Dispatchers.Default)
            .map(::toRelationships)

    override suspend fun getByTransactions(transactionIds: Collection<TransactionId>): List<Relationship> =
        withContext(Dispatchers.Default) {
            val rows =
                queryInChunks(transactionIds.map { it.id }.distinct(), MAX_IDS_PER_QUERY) { chunk ->
                    selectQueries
                        .selectByTransactions(chunk) { id, typeId, typeName, memberId, role, kind ->
                            MemberRow(id, typeId, typeName, memberId, role, kind)
                        }.awaitAsList()
                }
            // A relationship whose members fall into different chunks is returned by each of them.
            toRelationships(rows.distinct())
        }

    override suspend fun getHandMadeByTransactions(transactionIds: Collection<TransactionId>): List<Relationship> =
        withContext(Dispatchers.Default) {
            val rows =
                queryInChunks(transactionIds.map { it.id }.distinct(), MAX_IDS_PER_QUERY) { chunk ->
                    selectQueries
                        .selectHandMadeByTransactions(chunk) { id, typeId, typeName, memberId, role, kind ->
                            MemberRow(id, typeId, typeName, memberId, role, kind)
                        }.awaitAsList()
                }
            toRelationships(rows.distinct())
        }

    private data class MemberRow(
        val id: Long,
        val typeId: Long,
        val typeName: String,
        val memberId: Long,
        val role: Long,
        val kind: String,
    )

    private fun toRelationships(rows: List<MemberRow>): List<Relationship> =
        rows
            .groupBy { it.id }
            .map { (id, members) ->
                val first = members.first()
                Relationship(
                    id = RelationshipId(id),
                    relationshipType = RelationshipType(RelationshipTypeId(first.typeId), first.typeName),
                    members =
                        members.map { row ->
                            val transactionId: TransactionId = if (row.kind == "TRADE") TradeId(row.memberId) else TransferId(row.memberId)
                            RelationshipMember(transactionId, RelationshipRole.fromId(row.role))
                        },
                )
            }
}
