package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.repository.AttributeTypeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class AttributeTypeRepositoryImpl(
    database: MoneyManagerDatabase,
) : AttributeTypeRepository {
    private val queries = database.attributeTypeQueries

    override fun getAll(): Flow<List<AttributeType>> =
        queries.selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    AttributeType(
                        id = AttributeTypeId(row.id),
                        name = row.name,
                    )
                }
            }

    override fun getById(id: AttributeTypeId): Flow<AttributeType?> =
        queries.selectById(id.id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { row ->
                row?.let {
                    AttributeType(
                        id = AttributeTypeId(it.id),
                        name = it.name,
                    )
                }
            }

    override fun getByName(name: String): Flow<AttributeType?> =
        queries.selectByName(name)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { row ->
                row?.let {
                    AttributeType(
                        id = AttributeTypeId(it.id),
                        name = it.name,
                    )
                }
            }

    override suspend fun getOrCreate(name: String): AttributeTypeId =
        withContext(Dispatchers.Default) {
            queries.transactionWithResult {
                // Try to find existing
                val existing = queries.selectByName(name).executeAsOneOrNull()
                existing?.let { AttributeTypeId(it.id) }
                    ?: run {
                        // Insert and get the new ID
                        queries.insert(name)
                        val newId = queries.selectByName(name).executeAsOne().id
                        AttributeTypeId(newId)
                    }
            }
        }
}
