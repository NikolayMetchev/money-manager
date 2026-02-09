package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.PersonAccountOwnershipMapper
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.PersonAccountOwnership
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class PersonAccountOwnershipRepositoryImpl(
    database: MoneyManagerDatabase,
) : PersonAccountOwnershipRepository {
    private val queries = database.personQueries

    override fun getOwnershipsByPerson(personId: PersonId): Flow<List<PersonAccountOwnership>> =
        queries.ownershipSelectByPerson(personId.id)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(PersonAccountOwnershipMapper::mapList)

    override fun getOwnershipsByAccount(accountId: AccountId): Flow<List<PersonAccountOwnership>> =
        queries.ownershipSelectByAccount(accountId.id)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(PersonAccountOwnershipMapper::mapList)

    override fun getOwnershipById(id: Long): Flow<PersonAccountOwnership?> =
        queries.ownershipSelectById(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(PersonAccountOwnershipMapper::map) }

    override suspend fun createOwnership(
        personId: PersonId,
        accountId: AccountId,
    ): Long =
        withContext(Dispatchers.Default) {
            queries.transactionWithResult {
                queries.ownershipInsert(
                    person_id = personId.id,
                    account_id = accountId.id,
                )
                queries.ownershipLastInsertRowId().executeAsOne()
            }
        }

    override suspend fun deleteOwnership(id: Long): Unit =
        withContext(Dispatchers.Default) {
            queries.ownershipDelete(id)
        }

    override suspend fun deleteOwnershipsByPerson(personId: PersonId): Unit =
        withContext(Dispatchers.Default) {
            queries.ownershipDeleteByPerson(personId.id)
        }

    override suspend fun deleteOwnershipsByAccount(accountId: AccountId): Unit =
        withContext(Dispatchers.Default) {
            queries.ownershipDeleteByAccount(accountId.id)
        }
}
