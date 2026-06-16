package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.PersonAccountOwnershipMapper
import com.moneymanager.database.recordSource
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.PersonAccountOwnership
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class PersonAccountOwnershipRepositoryImpl(
    database: MoneyManagerDatabase,
    private val deviceId: DeviceId,
) : PersonAccountOwnershipRepository {
    private val queries = database.personQueries
    private val accountQueries = database.accountQueries
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
        source: Source,
    ): Long =
        withContext(Dispatchers.Default) {
            queries.transactionWithResult {
                queries.ownershipInsert(
                    person_id = personId.id,
                    account_id = accountId.id,
                )
                val id = queries.ownershipLastInsertRowId().executeAsOne()
                entitySourceQueries.recordSource(deviceId, EntityType.PERSON_ACCOUNT_OWNERSHIP, id, 1L, source)
                // A manual ownership change is a change to the account, so bump its revision and record
                // it in the account audit trail (the ownership change is matched to that revision in the
                // audit UI). Import/sample ownerships are part of bulk creation and don't bump.
                if (source is Source.Manual) {
                    accountQueries.bumpRevisionOnly(accountId.id)
                }
                id
            }
        }

    override suspend fun deleteOwnership(id: Long): Unit =
        withContext(Dispatchers.Default) {
            queries.transaction {
                // deleteOwnership is only reachable from manual account editing, so removing an owner is
                // a change to the account: bump its revision so it shows in the account audit trail.
                val accountId = queries.ownershipSelectById(id).executeAsOneOrNull()?.account_id
                queries.ownershipDelete(id)
                accountId?.let { accountQueries.bumpRevisionOnly(it) }
            }
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
