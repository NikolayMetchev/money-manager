package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.PersonAttribute
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.repository.PersonAttributeReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PersonAttributeReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : PersonAttributeReadRepository {
    private val selectQueries = database.personAttributeSelectQueries

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
                        groupKey = row.group_key,
                    )
                }
            }
}
