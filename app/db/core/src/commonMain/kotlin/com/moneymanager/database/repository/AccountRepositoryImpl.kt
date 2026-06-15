@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.mapper.AccountMapper
import com.moneymanager.database.mapper.TransferMapper
import com.moneymanager.database.recordEntityProvenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountMerge
import com.moneymanager.domain.model.AccountMergeContext
import com.moneymanager.domain.model.EntityProvenance
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.MergeId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.repository.AccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

class AccountRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
) : AccountRepository {
    private val queries = database.accountQueries
    private val transferQueries = database.transferQueries
    private val attributeQueries = database.accountAttributeQueries
    private val mergeQueries = database.accountMergeQueries
    private val personQueries = database.personQueries
    private val entitySourceQueries = database.entitySourceQueries

    override fun getAllAccounts(): Flow<List<Account>> =
        queries
            .selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(AccountMapper::mapList)

    override fun getAccountById(id: AccountId): Flow<Account?> =
        queries
            .selectById(id.id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(AccountMapper::map) }

    override suspend fun createAccount(
        account: Account,
        provenance: EntityProvenance,
    ): AccountId =
        withContext(Dispatchers.Default) {
            val id =
                queries.transactionWithResult {
                    queries.insert(
                        name = account.name,
                        opening_date = account.openingDate.toEpochMilliseconds(),
                        category_id = account.categoryId,
                    )
                    val newId = queries.lastInsertRowId().executeAsOne()
                    entitySourceQueries.recordEntityProvenance(EntityType.ACCOUNT, newId, 1L, provenance)
                    newId
                }
            AccountId(id)
        }

    override suspend fun createAccountsBatch(
        accounts: List<Account>,
        provenanceFor: (Account) -> EntityProvenance,
    ): List<AccountId> =
        withContext(Dispatchers.Default) {
            queries.transactionWithResult {
                accounts.map { account ->
                    queries.insert(
                        name = account.name,
                        opening_date = account.openingDate.toEpochMilliseconds(),
                        category_id = account.categoryId,
                    )
                    val id = queries.lastInsertRowId().executeAsOne()
                    entitySourceQueries.recordEntityProvenance(EntityType.ACCOUNT, id, 1L, provenanceFor(account))
                    AccountId(id)
                }
            }
        }

    override suspend fun updateAccount(
        account: Account,
        provenance: EntityProvenance,
    ): Long =
        withContext(Dispatchers.Default) {
            queries.transactionWithResult {
                queries.update(
                    name = account.name,
                    category_id = account.categoryId,
                    id = account.id.id,
                )
                val revision = queries.selectRevisionById(account.id.id).executeAsOne()
                entitySourceQueries.recordEntityProvenance(EntityType.ACCOUNT, account.id.id, revision, provenance)
                revision
            }
        }

    override suspend fun updateAccountWithAttributes(
        account: Account?,
        accountId: AccountId,
        deletedAttributeIds: Set<Long>,
        updatedAttributes: Map<Long, NewAttribute>,
        newAttributes: List<NewAttribute>,
        provenance: EntityProvenance,
    ): Long =
        withContext(Dispatchers.Default) {
            val effectiveAccountId = account?.id ?: accountId

            queries.transactionWithResult {
                val finalRevision =
                    updateEntityWithAttributes(
                        database = database,
                        hasEntityChanges = account != null,
                        deletedAttributeIds = deletedAttributeIds,
                        updatedAttributes = updatedAttributes,
                        newAttributes = newAttributes,
                        updateEntity = {
                            val accountToUpdate = requireNotNull(account)
                            queries.update(
                                name = accountToUpdate.name,
                                category_id = accountToUpdate.categoryId,
                                id = accountToUpdate.id.id,
                            )
                        },
                        bumpRevisionOnly = { queries.bumpRevisionOnly(effectiveAccountId.id) },
                        selectRevision = { queries.selectRevisionById(effectiveAccountId.id).executeAsOne() },
                        selectCurrentTypeId = { id ->
                            attributeQueries.selectById(id).executeAsOneOrNull()?.attribute_type_id
                        },
                        deleteById = { id -> attributeQueries.deleteById(id) },
                        insertAttribute = { attr ->
                            attributeQueries.insert(
                                account_id = effectiveAccountId.id,
                                attribute_type_id = attr.typeId.id,
                                attribute_value = attr.value,
                            )
                        },
                        updateValue = { value, id -> attributeQueries.updateValue(value, id) },
                    )
                entitySourceQueries.recordEntityProvenance(
                    EntityType.ACCOUNT,
                    effectiveAccountId.id,
                    finalRevision,
                    provenance,
                )
                finalRevision
            }
        }

    override suspend fun deleteAccount(id: AccountId): Unit =
        withContext(Dispatchers.Default) {
            queries.delete(id.id)
        }

    override suspend fun countTransfersByAccount(accountId: AccountId): Long =
        withContext(Dispatchers.Default) {
            transferQueries.countTransfersByAccount(accountId.id).executeAsOne()
        }

    override suspend fun getTransfersBetweenAccounts(
        accountA: AccountId,
        accountB: AccountId,
    ): List<Transfer> =
        withContext(Dispatchers.Default) {
            transferQueries
                .selectTransfersBetweenAccounts(
                    accountA = accountA.id,
                    accountB = accountB.id,
                    TransferMapper::mapRaw,
                ).executeAsList()
        }

    override suspend fun mergeAccounts(
        deletedAccount: AccountId,
        survivingAccount: AccountId,
    ): MergeId =
        withContext(Dispatchers.Default) {
            database.transactionWithResult {
                // Guard: transfers directly between the two accounts would become invalid
                // self-transfers (CHECK source != target) once reassigned, so refuse the merge.
                val between =
                    transferQueries
                        .selectTransfersBetweenAccounts(
                            accountA = deletedAccount.id,
                            accountB = survivingAccount.id,
                            TransferMapper::mapRaw,
                        ).executeAsList()
                require(between.isEmpty()) {
                    "Cannot merge: ${between.size} transaction(s) exist between the accounts"
                }

                // Snapshot the deleted account (it is gone after the merge) so it can be restored.
                val account = queries.selectById(deletedAccount.id).executeAsOne()
                val sourceTransferIds = transferQueries.selectTransferIdsBySourceAccount(deletedAccount.id).executeAsList()
                val targetTransferIds = transferQueries.selectTransferIdsByTargetAccount(deletedAccount.id).executeAsList()
                val attributes = attributeQueries.selectByAccount(deletedAccount.id).executeAsList()
                val ownerships = personQueries.ownershipSelectByAccount(deletedAccount.id).executeAsList()

                mergeQueries.insertMerge(
                    merged_at = Clock.System.now().toEpochMilliseconds(),
                    surviving_account_id = survivingAccount.id,
                    deleted_account_id = deletedAccount.id,
                    deleted_account_name = account.name,
                    deleted_account_opening_date = account.opening_date,
                    deleted_account_category_id = account.category_id,
                    deleted_account_revision_id = account.revision_id,
                )
                val mergeId = mergeQueries.lastInsertRowId().executeAsOne()

                val sourceSet = sourceTransferIds.toSet()
                val targetSet = targetTransferIds.toSet()
                (sourceSet + targetSet).forEach { transferId ->
                    mergeQueries.insertMergeTransfer(
                        merge_id = mergeId,
                        transfer_id = transferId,
                        moved_source = if (transferId in sourceSet) 1 else 0,
                        moved_target = if (transferId in targetSet) 1 else 0,
                    )
                }
                attributes.forEach {
                    mergeQueries.insertMergeAttribute(mergeId, it.attribute_type_id, it.attribute_value)
                }
                ownerships.forEach {
                    mergeQueries.insertMergeOwnership(mergeId, it.person_id)
                }

                transferQueries.moveTransfersSourceAccount(
                    targetAccount = survivingAccount.id,
                    accountToDelete = deletedAccount.id,
                )
                transferQueries.moveTransfersTargetAccount(
                    targetAccount = survivingAccount.id,
                    accountToDelete = deletedAccount.id,
                )
                queries.delete(deletedAccount.id)

                MergeId(mergeId)
            }
        }

    override fun getReversibleMerges(): Flow<List<AccountMerge>> =
        mergeQueries
            .selectReversible { id, mergedAt, survivingAccountId, deletedAccountId, deletedAccountName, transferCount ->
                AccountMerge(
                    id = MergeId(id),
                    mergedAt = Instant.fromEpochMilliseconds(mergedAt),
                    survivingAccountId = AccountId(survivingAccountId),
                    deletedAccountId = AccountId(deletedAccountId),
                    deletedAccountName = deletedAccountName,
                    transferCount = transferCount,
                )
            }.asFlow()
            .mapToList(Dispatchers.Default)

    override fun getMergesForSurvivingAccount(accountId: AccountId): Flow<List<AccountMerge>> =
        mergeQueries
            .selectBySurvivingAccount(accountId.id) {
                id,
                mergedAt,
                survivingAccountId,
                deletedAccountId,
                deletedAccountName,
                reversed,
                transferCount,
                ->
                AccountMerge(
                    id = MergeId(id),
                    mergedAt = Instant.fromEpochMilliseconds(mergedAt),
                    survivingAccountId = AccountId(survivingAccountId),
                    deletedAccountId = AccountId(deletedAccountId),
                    deletedAccountName = deletedAccountName,
                    transferCount = transferCount,
                    reversed = reversed != 0L,
                )
            }.asFlow()
            .mapToList(Dispatchers.Default)

    override suspend fun getMergesForDeletedAccount(accountId: AccountId): List<AccountMergeContext> =
        withContext(Dispatchers.Default) {
            mergeQueries
                .selectByDeletedAccount(accountId.id) { revisionId, survivingAccountId, reversed, survivingAccountName ->
                    AccountMergeContext(
                        deletedAccountRevisionId = revisionId,
                        survivingAccountId = AccountId(survivingAccountId),
                        survivingAccountName = survivingAccountName,
                        reversed = reversed != 0L,
                    )
                }.executeAsList()
        }

    override suspend fun unmergeAccount(mergeId: MergeId): Unit =
        withContext(Dispatchers.Default) {
            database.transaction {
                val merge = mergeQueries.selectById(mergeId.id).executeAsOneOrNull() ?: return@transaction
                if (merge.reversed != 0L) return@transaction

                // Recreate the merged-away account with its original id so the transfers reassigned
                // during the merge can be pointed back at it. Restore at the revision AFTER the one it
                // was deleted at, so the undo is recorded as a new forward revision in the audit trail
                // (rather than colliding with the merge's delete revision and looking like a fresh
                // account / wiped history).
                queries.insertWithId(
                    id = merge.deleted_account_id,
                    revision_id = merge.deleted_account_revision_id + 1,
                    name = merge.deleted_account_name,
                    opening_date = merge.deleted_account_opening_date,
                    category_id = merge.deleted_account_category_id,
                )

                // Restore attributes without bumping the account revision (preserve the snapshot revision).
                database.beginCreationMode()
                try {
                    mergeQueries.selectAttributesForMerge(mergeId.id).executeAsList().forEach {
                        attributeQueries.insert(
                            account_id = merge.deleted_account_id,
                            attribute_type_id = it.attribute_type_id,
                            attribute_value = it.attribute_value,
                        )
                    }
                } finally {
                    database.endCreationMode()
                }

                mergeQueries.selectOwnershipsForMerge(mergeId.id).executeAsList().forEach { personId ->
                    personQueries.ownershipInsert(person_id = personId, account_id = merge.deleted_account_id)
                }

                mergeQueries.selectTransfersForMerge(mergeId.id).executeAsList().forEach { row ->
                    if (row.moved_source != 0L) {
                        transferQueries.setTransferSourceAccount(sourceAccount = merge.deleted_account_id, id = row.transfer_id)
                    }
                    if (row.moved_target != 0L) {
                        transferQueries.setTransferTargetAccount(targetAccount = merge.deleted_account_id, id = row.transfer_id)
                    }
                }

                mergeQueries.markReversed(mergeId.id)
            }
        }
}
