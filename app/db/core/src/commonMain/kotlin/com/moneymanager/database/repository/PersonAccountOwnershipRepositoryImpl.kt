package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.PersonAccountOwnershipMapper
import com.moneymanager.database.recordEntityProvenance
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.EntityProvenance
import com.moneymanager.domain.model.EntityType
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
    private val entitySourceQueries = database.entitySourceQueries

    override fun getOwnershipsByPerson(personId: PersonId): Flow<List<PersonAccountOwnership>> =
        queries
            .ownershipSelectByPerson(personId.id)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(PersonAccountOwnershipMapper::mapList)

    override fun getOwnershipsByAccount(accountId: AccountId): Flow<List<PersonAccountOwnership>> =
        queries
            .ownershipSelectByAccount(accountId.id)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(PersonAccountOwnershipMapper::mapList)

    override fun getAllOwnerships(): Flow<List<PersonAccountOwnership>> =
        queries
            .ownershipSelectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(PersonAccountOwnershipMapper::mapList)

    override fun getOwnershipById(id: Long): Flow<PersonAccountOwnership?> =
        queries
            .ownershipSelectById(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(PersonAccountOwnershipMapper::map) }

    override suspend fun createOwnership(
        personId: PersonId,
        accountId: AccountId,
        provenance: EntityProvenance,
    ): Long =
        withContext(Dispatchers.Default) {
            queries.transactionWithResult {
                queries.ownershipInsert(
                    person_id = personId.id,
                    account_id = accountId.id,
                )
                val id = queries.ownershipLastInsertRowId().executeAsOne()
                entitySourceQueries.recordEntityProvenance(EntityType.PERSON_ACCOUNT_OWNERSHIP, id, 1L, provenance)
                id
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
