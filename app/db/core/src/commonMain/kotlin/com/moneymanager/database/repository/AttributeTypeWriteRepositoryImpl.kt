package com.moneymanager.database.repository

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.repository.AttributeTypeReadRepository
import com.moneymanager.domain.repository.AttributeTypeWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AttributeTypeWriteRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
    reader: AttributeTypeReadRepository,
) : AttributeTypeWriteRepository,
    AttributeTypeReadRepository by reader {
    private val selectQueries = database.attributeTypeSelectQueries
    private val writeQueries = database.attributeTypeWriteQueries

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
