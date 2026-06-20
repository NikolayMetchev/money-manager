package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.PersonAttribute
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.repository.PersonAttributeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class PersonAttributeRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
) : PersonAttributeRepository {
    private val selectQueries = database.personAttributeSelectQueries
    private val writeQueries = database.personAttributeWriteQueries

    override fun getByPerson(personId: PersonId): Flow<List<PersonAttribute>> =
        selectQueries
            .selectByPerson(personId.id)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    PersonAttribute(
                        id = row.id,
                        personId = PersonId(row.person_id),
                        attributeType =
                            AttributeType(
                                id = AttributeTypeId(row.attribute_type_id),
                                name = row.attribute_type_name,
                            ),
                        value = row.attribute_value,
                    )
                }
            }

    override suspend fun insert(
        personId: PersonId,
        attributeTypeId: AttributeTypeId,
        value: String,
    ): Long =
        withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                writeQueries.insert(personId.id, attributeTypeId.id, value)
                writeQueries.selectLastInsertedId().executeAsOne()
            }
        }

    override suspend fun insertInCreationMode(
        personId: PersonId,
        attributeTypeId: AttributeTypeId,
        value: String,
    ): Long =
        withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                database.beginCreationMode()
                try {
                    writeQueries.insert(personId.id, attributeTypeId.id, value)
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
