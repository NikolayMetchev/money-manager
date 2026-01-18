package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import kotlinx.coroutines.flow.Flow

interface PersonRepository {
    fun getAllPeople(): Flow<List<Person>>

    fun getPersonById(id: PersonId): Flow<Person?>

    suspend fun createPerson(person: Person): PersonId

    suspend fun updatePerson(person: Person)

    suspend fun deletePerson(id: PersonId)
}
