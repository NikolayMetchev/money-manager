package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.PendingManualEntry
import com.moneymanager.domain.model.RelationshipMember
import com.moneymanager.domain.model.RelationshipRole
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds

/** An amount a person typed for one [PendingManualEntry]. */
data class ManualEntryInput(
    val original: PendingManualEntry,
    val amount: Money,
)

/**
 * Records the amounts a person gave for transfers a manual-entry rule asked about: one transfer per input,
 * described [description] and linked to its original by a [relationshipType] relationship (the original is
 * the primary, so it stops being pending). It runs from the original's source to [counterpartyAccount]
 * (created by name if missing), or, when that is null, between the original's accounts reversed.
 *
 * With [excludedFromBalances] the entry is written excluded: the original's amount already contains it, so
 * counting it again would double it. The entry still reconciles, which is what it is recorded for.
 */
suspend fun ImportEngine.createManualEntries(
    inputs: List<ManualEntryInput>,
    description: String,
    relationshipType: String,
    counterpartyAccount: String?,
    excludedFromBalances: Boolean,
) {
    if (inputs.isEmpty()) return
    val counterpartyKey = LocalAccountKey("manual-entry-counterparty")
    val counterparty = counterpartyAccount?.let { AccountRef.Local(counterpartyKey) }
    val accounts =
        counterpartyAccount
            ?.let {
                listOf(
                    ImportAccountIntent(
                        key = counterpartyKey,
                        source = Source.Manual,
                        match = AccountMatchKey.ByName(it),
                        name = it,
                        openingDate = inputs.minOf { input -> input.original.timestamp },
                    ),
                )
            }.orEmpty()
    val exclusion =
        if (excludedFromBalances) {
            listOf(NewAttribute(AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID), "included in the amount of the original"))
        } else {
            emptyList()
        }
    val transfers =
        inputs.mapIndexed { index, (original, amount) ->
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
    val typeId = requireNotNull(created.relationshipTypeIds[relationshipType]) { "Relationship type $relationshipType was not resolved" }
    val links =
        inputs.mapIndexed { index, input ->
            val entryId = requireNotNull(created.createdTransferIds[ImportRowKey.Manual(index.toLong())]) { "Entry $index was not created" }
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
}
