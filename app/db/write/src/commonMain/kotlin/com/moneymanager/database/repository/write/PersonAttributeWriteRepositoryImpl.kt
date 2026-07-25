package com.moneymanager.database.repository.write

import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.repository.PersonAttributeReadRepository
import com.moneymanager.domain.repository.write.PersonAttributeWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PersonAttributeWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    reader: PersonAttributeReadRepository,
) : PersonAttributeWriteRepository,
    PersonAttributeReadRepository by reader {
    private val writeQueries = database.personAttributeWriteQueries

    override suspend fun insert(
        personId: PersonId,
        attributeTypeId: AttributeTypeId,
        value: String,
        groupKey: String,
    ): Long =
        withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                writeQueries.insert(personId.id, attributeTypeId.id, value, groupKey)
                writeQueries.selectLastInsertedId().executeAsOne()
            }
        }

    override suspend fun insertInCreationMode(
        personId: PersonId,
        attributeTypeId: AttributeTypeId,
        value: String,
        groupKey: String,
    ): Long =
        withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                database.beginCreationMode()
                try {
                    writeQueries.insert(personId.id, attributeTypeId.id, value, groupKey)
                    writeQueries.selectLastInsertedId().executeAsOne()
                } finally {
                    database.endCreationMode()
                }
            }
        }

    override suspend fun updateValue(
        id: Long,
        newValue: String,
    ): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.updateValue(newValue, id)
        }

    override suspend fun delete(id: Long): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.deleteById(id)
        }
}
