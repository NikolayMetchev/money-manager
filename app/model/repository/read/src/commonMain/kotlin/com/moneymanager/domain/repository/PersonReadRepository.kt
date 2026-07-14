package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import kotlinx.coroutines.flow.Flow

interface PersonReadRepository {
    fun getAllPeople(): Flow<List<Person>>

    fun getPersonById(id: PersonId): Flow<Person?>
}
