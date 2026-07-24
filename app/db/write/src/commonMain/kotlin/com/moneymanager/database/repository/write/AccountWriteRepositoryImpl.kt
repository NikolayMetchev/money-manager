package com.moneymanager.database.repository.write

import com.moneymanager.database.mapper.TransferMapper
import com.moneymanager.database.sql.accountAttribute.SelectByAccount
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.database.write.recordSource
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.MergeId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.write.AccountWriteRepository
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
                        selectCurrentSlot = { id ->
                            attributeSelectQueries.selectById(id).executeAsOneOrNull()?.let {
                                it.attribute_type_id to it.group_key
                            }
                        },
                        deleteById = { id -> attributeWriteQueries.deleteById(id) },
                        insertAttribute = { attr ->
                            attributeWriteQueries.insert(
                                account_id = effectiveAccountId.id,
                                attribute_type_id = attr.typeId.id,
                                attribute_value = attr.value,
                                group_key = attr.groupKey,
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

                // Carry the deleted account's attribute groups onto the survivor BEFORE the cascade delete
                // takes them away. Without this the merge destroys the loser's bank identity, so the next
                // import sees an unknown sort code / account number pair and re-creates the very account
                // just merged away ("merge loses identity, account resurrects").
                carryAttributeGroups(deletedAccount, survivingAccount, mergeId)

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
                // account.name is UNIQUE: another account may have taken the deleted account's name since
                // the merge. Disambiguate with a counter rather than let insertWithId throw a raw
                // constraint violation.
                val takenNames =
                    selectQueries
                        .selectAll()
                        .executeAsList()
                        .map { it.name }
                        .toSet()
                val restoredName = uniqueRestoreName(merge.deleted_account_name, takenNames)
                writeQueries.insertWithId(
                    id = merge.deleted_account_id,
                    revision_id = restoredRevision,
                    name = restoredName,
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
                // this account is exactly the set removed by this merge. group_key is restored with the
                // value — an account that owned two bank identities would otherwise come back with both
                // pairs collapsed into the ungrouped slot, violating the UNIQUE constraint.
                database.beginCreationMode()
                try {
                    auditQueries.selectDeletedAttributesForAccount(merge.deleted_account_id).executeAsList().forEach {
                        attributeWriteQueries.insert(
                            account_id = merge.deleted_account_id,
                            attribute_type_id = it.attribute_type_id,
                            attribute_value = it.attribute_value,
                            group_key = it.group_key,
                        )
                    }
                    // Remove the attributes the merge grafted onto the SURVIVOR. Leaving them would have
                    // both accounts claiming the same bank identity, making the next import's lookup
                    // non-deterministic (whichever account id the index happens to see first wins).
                    mergeSelectQueries.selectAttributeIdsForMerge(mergeId.id).executeAsList().forEach { attributeId ->
                        attributeWriteQueries.deleteById(attributeId)
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

    /**
     * Moves [deletedAccount]'s attributes onto [survivingAccount], recording each row actually inserted in
     * `account_merge_attribute` so [unmergeAccount] can remove exactly what was added and leave the
     * survivor's own attributes alone.
     *
     * Runs in creation mode: each acquired attribute is recorded in `account_attribute_audit` at the
     * survivor's current revision without minting one unsourced revision per attribute (the same choice
     * unmerge already makes when restoring).
     *
     * Nothing is ever overwritten. A conflicting value is inserted under a fresh group instead, so merging
     * two accounts that each carry a different `account-external-id` keeps both and the survivor stays
     * matchable by either provider.
     */
    private fun carryAttributeGroups(
        deletedAccount: AccountId,
        survivingAccount: AccountId,
        mergeId: Long,
    ) {
        val loserAttrs = attributeSelectQueries.selectByAccount(deletedAccount.id).executeAsList()
        if (loserAttrs.isEmpty()) return
        val survivor = SurvivorAttributes(attributeSelectQueries.selectByAccount(survivingAccount.id).executeAsList())

        database.beginCreationMode()
        try {
            for ((groupKey, group) in loserAttrs.groupBy { it.group_key }) {
                if (groupKey.isEmpty()) {
                    carryUngrouped(group, survivor, survivingAccount, mergeId)
                } else {
                    carryGroup(groupKey, group, survivor, survivingAccount, mergeId)
                }
            }
        } finally {
            database.endCreationMode()
        }
    }

    /**
     * Ungrouped attributes carry one at a time: they are independent scalars, not a tuple. A conflicting
     * value goes into a group of its own rather than overwriting, so merging two provider-created accounts
     * keeps both external ids and the survivor stays matchable by either.
     */
    private fun carryUngrouped(
        group: List<SelectByAccount>,
        survivor: SurvivorAttributes,
        survivingAccount: AccountId,
        mergeId: Long,
    ) {
        group.forEach { attr ->
            if (survivor.holds(attr.attribute_type_id, attr.attribute_value)) return@forEach
            val target = if (survivor.occupiesUngrouped(attr.attribute_type_id)) "m$mergeId:${attr.id}" else ""
            insertCarriedAttribute(survivingAccount, attr.attribute_type_id, attr.attribute_value, target, mergeId)
        }
    }

    /**
     * Carries one whole identity group onto the survivor, unless the survivor already holds that identity.
     *
     * Because stored group keys are opaque UUIDs, two accounts describing the same real bank account carry
     * that identity under DIFFERENT keys, so the match must be by the identity's derived value (sort code +
     * account number), not by the key string — otherwise every same-identity merge would duplicate it.
     * A group with no derivable bank identity (an arbitrary user group) falls back to key-string matching,
     * which for UUIDs effectively never collides, so it is carried across verbatim.
     */
    private fun carryGroup(
        groupKey: String,
        group: List<SelectByAccount>,
        survivor: SurvivorAttributes,
        survivingAccount: AccountId,
        mergeId: Long,
    ) {
        // Same identity already on the survivor (under whatever key) -> nothing to carry.
        bankKeyOf(group)?.let { if (survivor.hasBankKey(it)) return }

        val existing = survivor.group(groupKey)
        if (existing != null && existing == group.associate { it.attribute_type_id to it.attribute_value }) return
        // A key-string collision with unrelated contents (a stale/hand-edited key) is re-keyed rather than
        // dropped or overwritten; safe because import resolves the group by derived value, not by key.
        val target = if (existing == null) groupKey else "$groupKey#m$mergeId"
        group.forEach { attr ->
            insertCarriedAttribute(survivingAccount, attr.attribute_type_id, attr.attribute_value, target, mergeId)
        }
    }

    /**
     * The bank identity a group encodes — `"<sortCode>|<accountNumber>"` — when it holds exactly one sort
     * code and one account number, else null. Derived from the values (never the key), mirroring the import
     * engine's `bankKeysFrom`, so two accounts holding the same identity under different UUID keys still
     * reconcile on merge.
     */
    private fun bankKeyOf(group: List<SelectByAccount>): String? {
        val sortCode = group.singleOrNull { it.attribute_type_id == WellKnownIds.ACCOUNT_SORT_CODE_ATTR_TYPE_ID }?.attribute_value
        val accountNumber =
            group.singleOrNull { it.attribute_type_id == WellKnownIds.ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID }?.attribute_value
        return if (!sortCode.isNullOrBlank() && !accountNumber.isNullOrBlank()) "$sortCode|$accountNumber" else null
    }

    /** The surviving account's attributes, indexed for the lookups [carryAttributeGroups] needs. */
    private inner class SurvivorAttributes(
        attrs: List<SelectByAccount>,
    ) {
        private val groups =
            attrs
                .filter { it.group_key.isNotEmpty() }
                .groupBy { it.group_key }
                .mapValues { (_, rows) -> rows.associate { it.attribute_type_id to it.attribute_value } }
        private val ungroupedTypes = attrs.filter { it.group_key.isEmpty() }.map { it.attribute_type_id }.toSet()
        private val typeValues = attrs.map { it.attribute_type_id to it.attribute_value }.toSet()

        // Bank identities the survivor already holds, one per group (grouped or, tolerating legacy data,
        // the ungrouped slot). Lets a same-identity loser group be recognised despite a different key.
        private val bankKeys =
            (attrs.filter { it.group_key.isNotEmpty() }.groupBy { it.group_key }.values + listOf(attrs.filter { it.group_key.isEmpty() }))
                .mapNotNull { bankKeyOf(it) }
                .toSet()

        fun group(key: String): Map<Long, String>? = groups[key]

        fun occupiesUngrouped(typeId: Long): Boolean = typeId in ungroupedTypes

        fun hasBankKey(key: String): Boolean = key in bankKeys

        fun holds(
            typeId: Long,
            value: String,
        ): Boolean = (typeId to value) in typeValues
    }

    /** Inserts one carried attribute onto the survivor and records it against [mergeId] for unmerge. */
    private fun insertCarriedAttribute(
        survivingAccount: AccountId,
        attributeTypeId: Long,
        value: String,
        groupKey: String,
        mergeId: Long,
    ) {
        attributeWriteQueries.insert(
            account_id = survivingAccount.id,
            attribute_type_id = attributeTypeId,
            attribute_value = value,
            group_key = groupKey,
        )
        val attributeId = attributeWriteQueries.selectLastInsertedId().executeAsOne()
        mergeWriteQueries.insertMergeAttribute(merge_id = mergeId, attribute_id = attributeId)
    }

    /** [desired] if free, else [desired] with an incrementing counter suffix until one is (always terminates). */
    private fun uniqueRestoreName(
        desired: String,
        taken: Set<String>,
    ): String {
        if (desired !in taken) return desired
        return generateSequence(2) { it + 1 }.map { "$desired ($it)" }.first { it !in taken }
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
