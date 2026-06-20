package com.moneymanager.database.repository

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.recordSource
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.PersonAccountOwnershipReadRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PersonAccountOwnershipWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    reader: PersonAccountOwnershipReadRepository,
) : PersonAccountOwnershipWriteRepository,
    PersonAccountOwnershipReadRepository by reader {
    private val personSelectQueries = database.personSelectQueries
    private val personWriteQueries = database.personWriteQueries
    private val accountWriteQueries = database.accountWriteQueries

    override suspend fun createOwnership(
        personId: PersonId,
        accountId: AccountId,
        source: Source,
    ): Long =
        withContext(Dispatchers.Default) {
            personWriteQueries.transactionWithResult {
                personWriteQueries.ownershipInsert(
                    person_id = personId.id,
                    account_id = accountId.id,
                )
                val id = personWriteQueries.ownershipLastInsertRowId().executeAsOne()
                database.recordSource(deviceId, EntityType.PERSON_ACCOUNT_OWNERSHIP, id, 1L, source)
                // A manual ownership change is a change to the account, so bump its revision and record
                // it in the account audit trail (the ownership change is matched to that revision in the
                // audit UI). Import/sample ownerships are part of bulk creation and don't bump.
                if (source is Source.Manual) {
                    accountWriteQueries.bumpRevisionOnly(accountId.id)
                }
                id
            }
        }

    override suspend fun deleteOwnership(id: Long): Unit =
        withContext(Dispatchers.Default) {
            personWriteQueries.transaction {
                // deleteOwnership is only reachable from manual account editing, so removing an owner is
                // a change to the account: bump its revision so it shows in the account audit trail.
                val accountId = personSelectQueries.ownershipSelectById(id).executeAsOneOrNull()?.account_id
                personWriteQueries.ownershipDelete(id)
                accountId?.let { accountWriteQueries.bumpRevisionOnly(it) }
            }
        }

    override suspend fun deleteOwnershipsByPerson(personId: PersonId): Unit =
        withContext(Dispatchers.Default) {
            personWriteQueries.ownershipDeleteByPerson(personId.id)
        }

    override suspend fun deleteOwnershipsByAccount(accountId: AccountId): Unit =
        withContext(Dispatchers.Default) {
            personWriteQueries.ownershipDeleteByAccount(accountId.id)
        }
}
