package com.moneymanager.domain.repository

import com.moneymanager.domain.model.PersonAttribute
import com.moneymanager.domain.model.PersonId
import kotlinx.coroutines.flow.Flow

interface PersonAttributeReadRepository {
    fun getByPerson(personId: PersonId): Flow<List<PersonAttribute>>

    /** Every person attribute in one query — lets callers build an index without an N+1 per person. */
    fun getAll(): Flow<List<PersonAttribute>>
}
