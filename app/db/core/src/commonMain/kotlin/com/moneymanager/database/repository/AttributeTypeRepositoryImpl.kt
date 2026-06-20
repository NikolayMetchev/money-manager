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
    private val selectQueries = database.attributeTypeSelectQueries
    private val writeQueries = database.attributeTypeWriteQueries

    override fun getAll(): Flow<List<AttributeType>> =
        selectQueries
            .selectAll()
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
        selectQueries
            .selectById(id.id)
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
        selectQueries
            .selectByName(name)
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
            writeQueries.transactionWithResult {
                // Try to find existing
                val existing = selectQueries.selectByName(name).executeAsOneOrNull()
                existing?.let { AttributeTypeId(it.id) }
                    ?: run {
                        // Insert and get the new ID
                        writeQueries.insert(name)
                        val newId = selectQueries.selectByName(name).executeAsOne().id
                        AttributeTypeId(newId)
                    }
            }
        }
}
