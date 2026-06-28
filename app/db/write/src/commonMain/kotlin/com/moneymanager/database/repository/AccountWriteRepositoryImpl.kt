@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.mapper.TransferMapper
import com.moneymanager.database.recordSource
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.MergeId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.AccountWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

class AccountWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    reader: AccountReadRepository,
) : AccountWriteRepository,
    AccountReadRepository by reader {
    private val selectQueries = database.accountSelectQueries
    private val writeQueries = database.accountWriteQueries
    private val transferSelectQueries = database.transferSelectQueries
    private val transferWriteQueries = database.transferWriteQueries
    private val attributeSelectQueries = database.accountAttributeSelectQueries
    private val attributeWriteQueries = database.accountAttributeWriteQueries
    private val mergeSelectQueries = database.accountMergeSelectQueries
    private val mergeWriteQueries = database.accountMergeWriteQueries
    private val personWriteQueries = database.personWriteQueries
    private val auditQueries = database.auditSelectQueries

    override suspend fun createAccount(
        account: Account,
        source: Source,
    ): AccountId =
        withContext(Dispatchers.Default) {
            val id =
                writeQueries.transactionWithResult {
                    writeQueries.insert(
                        name = account.name,
                        opening_date = account.openingDate.toEpochMilliseconds(),
                        category_id = account.categoryId,
                    )
                    val newId = writeQueries.lastInsertRowId().executeAsOne()
                    database.recordSource(deviceId, EntityType.ACCOUNT, newId, 1L, source)
                    newId
                }
            AccountId(id)
        }

    override suspend fun createAccountsBatch(
        accounts: List<Account>,
        sourceFor: (Account) -> Source,
    ): List<AccountId> =
        withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                accounts.map { account ->
                    writeQueries.insert(
                        name = account.name,
                        opening_date = account.openingDate.toEpochMilliseconds(),
                        category_id = account.categoryId,
                    )
                    val id = writeQueries.lastInsertRowId().executeAsOne()
                    database.recordSource(deviceId, EntityType.ACCOUNT, id, 1L, sourceFor(account))
                    AccountId(id)
                }
            }
        }

    override suspend fun updateAccount(
        account: Account,
        source: Source,
    ): Long =
        withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                writeQueries.update(
                    name = account.name,
                    category_id = account.categoryId,
                    id = account.id.id,
                )
                val revision = selectQueries.selectRevisionById(account.id.id).executeAsOne()
                database.recordSource(deviceId, EntityType.ACCOUNT, account.id.id, revision, source)
                revision
            }
        }

    override suspend fun updateAccountWithAttributes(
        account: Account?,
        accountId: AccountId,
        deletedAttributeIds: Set<Long>,
        updatedAttributes: Map<Long, NewAttribute>,
        newAttributes: List<NewAttribute>,
        source: Source,
    ): Long =
        withContext(Dispatchers.Default) {
            val effectiveAccountId = account?.id ?: accountId

            writeQueries.transactionWithResult {
                val finalRevision =
                    updateEntityWithAttributes(
                        database = database,
                        hasEntityChanges = account != null,
                        deletedAttributeIds = deletedAttributeIds,
                        updatedAttributes = updatedAttributes,
                        newAttributes = newAttributes,
                        updateEntity = {
                            val accountToUpdate = requireNotNull(account)
                            writeQueries.update(
                                name = accountToUpdate.name,
                                category_id = accountToUpdate.categoryId,
                                id = accountToUpdate.id.id,
                            )
                        },
                        bumpRevisionOnly = { writeQueries.bumpRevisionOnly(effectiveAccountId.id) },
                        selectRevision = { selectQueries.selectRevisionById(effectiveAccountId.id).executeAsOne() },
                        selectCurrentTypeId = { id ->
                            attributeSelectQueries.selectById(id).executeAsOneOrNull()?.attribute_type_id
                        },
                        deleteById = { id -> attributeWriteQueries.deleteById(id) },
                        insertAttribute = { attr ->
                            attributeWriteQueries.insert(
                                account_id = effectiveAccountId.id,
                                attribute_type_id = attr.typeId.id,
                                attribute_value = attr.value,
                            )
                        },
                        updateValue = { value, id -> attributeWriteQueries.updateValue(value, id) },
                    )
                database.recordSource(
                    deviceId,
                    EntityType.ACCOUNT,
                    effectiveAccountId.id,
                    finalRevision,
                    source,
                )
                finalRevision
            }
        }

    override suspend fun deleteAccount(id: AccountId): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.delete(id.id)
        }

    override suspend fun mergeAccounts(
        deletedAccount: AccountId,
        survivingAccount: AccountId,
    ): MergeId =
        withContext(Dispatchers.Default) {
            database.transactionWithResult {
                // Merging an account into itself would reassign its transfers to itself and then delete
                // it, destroying the "surviving" account; refuse it.
                require(deletedAccount != survivingAccount) { "Cannot merge an account into itself" }

                // Guard: transfers directly between the two accounts would become invalid
                // self-transfers (CHECK source != target) once reassigned, so refuse the merge.
                val between =
                    transferSelectQueries
                        .selectTransfersBetweenAccounts(
                            accountA = deletedAccount.id,
                            accountB = survivingAccount.id,
                            TransferMapper::mapRaw,
                        ).executeAsList()
                require(between.isEmpty()) {
                    "Cannot merge: ${between.size} transaction(s) exist between the accounts"
                }

                // Snapshot only the deleted account's core fields (name/opening date/category/revision),
                // which the cascade delete leaves no trace of. Attributes and ownerships are NOT
                // snapshotted: the cascade delete records each one as a DELETE in account_attribute_audit
                // / person_account_ownership_audit, so unmerge reconstructs them from the audit trail.
                val account = selectQueries.selectById(deletedAccount.id).executeAsOne()
                val sourceTransferIds = transferSelectQueries.selectTransferIdsBySourceAccount(deletedAccount.id).executeAsList()
                val targetTransferIds = transferSelectQueries.selectTransferIdsByTargetAccount(deletedAccount.id).executeAsList()

                mergeWriteQueries.insertMerge(
                    merged_at = Clock.System.now().toEpochMilliseconds(),
                    surviving_account_id = survivingAccount.id,
                    deleted_account_id = deletedAccount.id,
                    deleted_account_name = account.name,
                    deleted_account_opening_date = account.opening_date,
                    deleted_account_category_id = account.category_id,
                    // The delete is recorded as its own revision (OLD.revision_id + 1, see the custom
                    // account delete trigger); store that so the merge note matches the DELETE entry and
                    // unmerge recreates the account at the next revision after it.
                    deleted_account_revision_id = account.revision_id + 1,
                )
                val mergeId = mergeWriteQueries.lastInsertRowId().executeAsOne()

                val sourceSet = sourceTransferIds.toSet()
                val targetSet = targetTransferIds.toSet()
                (sourceSet + targetSet).forEach { transferId ->
                    mergeWriteQueries.insertMergeTransfer(
                        merge_id = mergeId,
                        transfer_id = transferId,
                        moved_source = if (transferId in sourceSet) 1 else 0,
                        moved_target = if (transferId in targetSet) 1 else 0,
                    )
                }
                transferWriteQueries.moveTransfersSourceAccount(
                    targetAccount = survivingAccount.id,
                    accountToDelete = deletedAccount.id,
                )
                transferWriteQueries.moveTransfersTargetAccount(
                    targetAccount = survivingAccount.id,
                    accountToDelete = deletedAccount.id,
                )
                // The reassignment bumped each affected transfer's revision; source those new revisions
                // as a merge so the transaction audit trail isn't left with "source data missing".
                (sourceSet + targetSet).forEach { transferId ->
                    recordTransferMergeSource(transferId, Source.Merge)
                }

                writeQueries.delete(deletedAccount.id)
                // Source the deleted account's DELETE audit entry (recorded at revision_id + 1 by the
                // custom account delete trigger) as a merge, so the trail shows where it went rather than
                // "source data missing". entity_source is independent of the now-deleted account row.
                database.recordSource(
                    deviceId,
                    EntityType.ACCOUNT,
                    deletedAccount.id,
                    account.revision_id + 1,
                    Source.Merge,
                )

                MergeId(mergeId)
            }
        }

    override suspend fun unmergeAccount(mergeId: MergeId): Unit =
        withContext(Dispatchers.Default) {
            database.transaction {
                val merge = mergeSelectQueries.selectById(mergeId.id).executeAsOneOrNull() ?: return@transaction
                if (merge.reversed != 0L) return@transaction

                // Recreate the merged-away account with its original id so the transfers reassigned
                // during the merge can be pointed back at it. Restore at the revision AFTER the one it
                // was deleted at, so the undo is recorded as a new forward revision in the audit trail
                // (rather than colliding with the merge's delete revision and looking like a fresh
                // account / wiped history).
                val restoredRevision = merge.deleted_account_revision_id + 1
                writeQueries.insertWithId(
                    id = merge.deleted_account_id,
                    revision_id = restoredRevision,
                    name = merge.deleted_account_name,
                    opening_date = merge.deleted_account_opening_date,
                    category_id = merge.deleted_account_category_id,
                )
                // Source the recreated account's INSERT audit entry as a merge-undo so the trail shows
                // where it came from (rather than "source data missing").
                database.recordSource(
                    deviceId,
                    EntityType.ACCOUNT,
                    merge.deleted_account_id,
                    restoredRevision,
                    Source.Unmerge,
                )

                // Restore attributes from the audit trail (no bump): the merge's cascade delete recorded
                // each attribute as a DELETE in account_attribute_audit, and the latest such batch for
                // this account is exactly the set removed by this merge.
                database.beginCreationMode()
                try {
                    auditQueries.selectDeletedAttributesForAccount(merge.deleted_account_id).executeAsList().forEach {
                        attributeWriteQueries.insert(
                            account_id = merge.deleted_account_id,
                            attribute_type_id = it.attribute_type_id,
                            attribute_value = it.attribute_value,
                        )
                    }
                } finally {
                    database.endCreationMode()
                }

                // Restore ownerships from the audit trail: the merge's cascade delete recorded each
                // ownership as a DELETE in person_account_ownership_audit, and the latest such batch for
                // this account is exactly the set deleted by this merge.
                auditQueries.selectDeletedOwnershipPersonIds(merge.deleted_account_id).executeAsList().forEach { personId ->
                    personWriteQueries.ownershipInsert(person_id = personId, account_id = merge.deleted_account_id)
                }

                mergeSelectQueries.selectTransfersForMerge(mergeId.id).executeAsList().forEach { row ->
                    if (row.moved_source != 0L) {
                        transferWriteQueries.setTransferSourceAccount(sourceAccount = merge.deleted_account_id, id = row.transfer_id)
                    }
                    if (row.moved_target != 0L) {
                        transferWriteQueries.setTransferTargetAccount(targetAccount = merge.deleted_account_id, id = row.transfer_id)
                    }
                    // Moving a transfer back bumped its revision; source that new revision as a merge-undo.
                    recordTransferMergeSource(row.transfer_id, Source.Unmerge)
                }

                mergeWriteQueries.markReversed(mergeId.id)
            }
        }

    /** Records [transferId]'s current revision in entity_source for a merge/unmerge [source]. */
    private fun recordTransferMergeSource(
        transferId: Long,
        source: Source,
    ) {
        database.recordSource(
            deviceId = deviceId,
            entityType = EntityType.TRANSFER,
            entityId = transferId,
            revisionId = transferSelectQueries.selectRevisionById(transferId).executeAsOne(),
            source = source,
        )
    }
}
