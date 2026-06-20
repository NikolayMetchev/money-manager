package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.RelationshipType
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.repository.RelationshipTypeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RelationshipTypeRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
) : RelationshipTypeRepository {
    private val selectQueries = database.relationshipTypeSelectQueries
    private val writeQueries = database.relationshipTypeWriteQueries

    override fun getAll(): Flow<List<RelationshipType>> =
        selectQueries
            .selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    RelationshipType(
                        id = RelationshipTypeId(row.id),
                        name = row.name,
                    )
                }
            }

    override fun getById(id: RelationshipTypeId): Flow<RelationshipType?> =
        selectQueries
            .selectById(id.id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { row ->
                row?.let {
                    RelationshipType(
                        id = RelationshipTypeId(it.id),
                        name = it.name,
                    )
                }
            }

    override fun getByName(name: String): Flow<RelationshipType?> =
        selectQueries
            .selectByName(name)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { row ->
                row?.let {
                    RelationshipType(
                        id = RelationshipTypeId(it.id),
                        name = it.name,
                    )
                }
            }

    override suspend fun getOrCreate(name: String): RelationshipTypeId =
        withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                val existing = selectQueries.selectByName(name).executeAsOneOrNull()
                existing?.let { RelationshipTypeId(it.id) }
                    ?: run {
                        writeQueries.insert(name)
                        val newId = selectQueries.selectByName(name).executeAsOne().id
                        RelationshipTypeId(newId)
                    }
            }
        }
}
