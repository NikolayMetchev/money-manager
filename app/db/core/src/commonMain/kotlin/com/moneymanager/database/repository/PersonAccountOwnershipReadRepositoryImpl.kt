package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.PersonAccountOwnershipMapper
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.PersonAccountOwnership
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.repository.PersonAccountOwnershipReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PersonAccountOwnershipReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : PersonAccountOwnershipReadRepository {
    private val personSelectQueries = database.personSelectQueries

    override fun getOwnershipsByPerson(personId: PersonId): Flow<List<PersonAccountOwnership>> =
        personSelectQueries
            .ownershipSelectByPerson(personId.id)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(PersonAccountOwnershipMapper::mapList)

    override fun getOwnershipsByAccount(accountId: AccountId): Flow<List<PersonAccountOwnership>> =
        personSelectQueries
            .ownershipSelectByAccount(accountId.id)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(PersonAccountOwnershipMapper::mapList)

    override fun getAllOwnerships(): Flow<List<PersonAccountOwnership>> =
        personSelectQueries
            .ownershipSelectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(PersonAccountOwnershipMapper::mapList)

    override fun getOwnershipById(id: Long): Flow<PersonAccountOwnership?> =
        personSelectQueries
            .ownershipSelectById(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(PersonAccountOwnershipMapper::map) }
}
