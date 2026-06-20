package com.moneymanager.database.repository

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.repository.RelationshipTypeReadRepository
import com.moneymanager.domain.repository.RelationshipTypeWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RelationshipTypeWriteRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
    reader: RelationshipTypeReadRepository,
) : RelationshipTypeWriteRepository,
    RelationshipTypeReadRepository by reader {
    private val selectQueries = database.relationshipTypeSelectQueries
    private val writeQueries = database.relationshipTypeWriteQueries

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
