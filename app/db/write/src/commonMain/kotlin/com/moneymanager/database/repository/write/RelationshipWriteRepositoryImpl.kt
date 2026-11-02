package com.moneymanager.database.repository.write

import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.database.write.insertRelationship
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.RelationshipId
import com.moneymanager.domain.model.RelationshipMember
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.RelationshipReadRepository
import com.moneymanager.domain.repository.write.RelationshipWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RelationshipWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    reader: RelationshipReadRepository,
) : RelationshipWriteRepository,
    RelationshipReadRepository by reader {
    private val writeQueries = database.relationshipWriteQueries

    override suspend fun create(
        typeId: RelationshipTypeId,
        members: List<RelationshipMember>,
        source: Source,
    ): RelationshipId? =
        withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                database
                    .insertRelationship(deviceId, typeId.id, members.map { it.transactionId.id to it.role }, source)
                    ?.let(::RelationshipId)
            }
        }

    override suspend fun delete(id: RelationshipId): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.deleteById(id.id)
        }
}
