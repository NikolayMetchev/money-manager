package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.PendingManualEntry
import com.moneymanager.domain.model.RelationshipMember
import com.moneymanager.domain.model.RelationshipRole
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** An amount a person typed for one [PendingManualEntry]. */
data class ManualEntryInput(
    val original: PendingManualEntry,
    val amount: Money,
)

/**
 * Records the amounts a person gave for transfers a manual-entry rule asked about: one transfer per input,
 * described [description] and linked to its original by a [relationshipType] relationship (the original is
 * the primary, so it stops being pending). A "fee" entry ([WellKnownIds.FEE_RELATIONSHIP_TYPE_NAME]) runs from the original's source to that account's
 * fee account ([AccountMatchKey.FeeAccountOf]); otherwise between the original's accounts reversed.
 *
 * With [excludedFromBalances] the entry is written excluded: the original's amount already contains it, so
 * counting it again would double it. The entry still reconciles, which is what it is recorded for.
 *
 * Returns the entries' ids, in the order of [inputs].
 */
suspend fun ImportEngine.createManualEntries(
    inputs: List<ManualEntryInput>,
    description: String,
    relationshipType: String,
    excludedFromBalances: Boolean,
): List<TransferId> {
    if (inputs.isEmpty()) return emptyList()
    val feeAccountKeys =
        if (relationshipType == WellKnownIds.FEE_RELATIONSHIP_TYPE_NAME) {
            inputs.map { it.original.sourceAccountId }.distinct().associateWith { LocalAccountKey("manual-entry-fee-account-of-${it.id}") }
        } else {
            emptyMap()
        }
    val openingDate = inputs.minOf { input -> input.original.timestamp }
    val accounts =
        feeAccountKeys.map { (account, key) ->
            ImportAccountIntent(
                key = key,
                source = Source.Manual,
                match = AccountMatchKey.FeeAccountOf(AccountRef.Existing(account)),
                openingDate = openingDate,
            )
        }
    val exclusion =
        if (excludedFromBalances) {
            listOf(NewAttribute(AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID), "included in the amount of the original"))
        } else {
            emptyList()
        }
    val transfers =
        inputs.mapIndexed { index, (original, amount) ->
            val counterparty = feeAccountKeys[original.sourceAccountId]?.let { AccountRef.Local(it) }
            ImportTransfer(
                source = Source.Manual,
                rowKey = ImportRowKey.Manual(index.toLong()),
                fromAccount = AccountRef.Existing(if (counterparty != null) original.sourceAccountId else original.targetAccountId),
                toAccount = counterparty ?: AccountRef.Existing(original.sourceAccountId),
                timestamp = original.timestamp,
                description = description,
                amount = amount,
                attributes = exclusion,
            )
        }
    val created =
        import(
            ImportBatch(
                transfers = transfers,
                dedupePolicy = DedupePolicy.None,
                accountsToCreate = accounts,
                relationshipTypeNames = listOf(relationshipType),
            ),
        )
    val entryIds =
        inputs.indices.map { requireNotNull(created.createdTransferIds[ImportRowKey.Manual(it.toLong())]) { "Entry $it was not created" } }
    // The links need the entries' ids, so they are a second write. If it fails, the entries must not stay: their
    // originals would still be pending and a retry would book every entry twice.
    var linked = false
    try {
        val typeId =
            requireNotNull(created.relationshipTypeIds[relationshipType]) { "Relationship type $relationshipType was not resolved" }
        val links =
            inputs.mapIndexed { index, input ->
                val entryId =
                    requireNotNull(created.createdTransferIds[ImportRowKey.Manual(index.toLong())]) { "Entry $index was not created" }
                RelationshipMutation.Create(
                    typeId = typeId,
                    members =
                        listOf(
                            RelationshipMember(input.original.transferId, RelationshipRole.PRIMARY),
                            RelationshipMember(entryId, RelationshipRole.RELATED),
                        ),
                    source = Source.Manual,
                )
            }
        import(ImportBatch(relationshipMutations = links))
        linked = true
    } finally {
        if (!linked) withContext(NonCancellable) { deleteManualEntries(created.createdTransferIds.values) }
    }
    return entryIds
}

/** Deletes the entries [createManualEntries] made (and so the links that tie them to their originals), putting the originals back to pending. */
suspend fun ImportEngine.deleteManualEntries(entries: Collection<TransferId>) {
    if (entries.isEmpty()) return
    import(
        ImportBatch(
            transfers = entries.map { ImportTransfer(source = Source.Manual, operation = ImportOperation.DELETE, existingId = it) },
            dedupePolicy = DedupePolicy.None,
        ),
    )
}
