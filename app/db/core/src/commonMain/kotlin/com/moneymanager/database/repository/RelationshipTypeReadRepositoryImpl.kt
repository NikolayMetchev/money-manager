package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.RelationshipType
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.repository.RelationshipTypeReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RelationshipTypeReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : RelationshipTypeReadRepository {
    private val selectQueries = database.relationshipTypeSelectQueries

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
}
