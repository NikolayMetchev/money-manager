package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.PersonMapper
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.repository.PersonReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PersonReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : PersonReadRepository {
    private val personSelectQueries = database.personSelectQueries

    override fun getAllPeople(): Flow<List<Person>> =
        personSelectQueries
            .selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(PersonMapper::mapList)

    override fun getPersonById(id: PersonId): Flow<Person?> =
        personSelectQueries
            .selectById(id.id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(PersonMapper::map) }
}
