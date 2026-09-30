package com.moneymanager.importengineapi

import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository

/** Value an internal-transfer reconcile's exclusion attribute always carries (see `ImportDeduper`). */
private const val RECONCILED_EXCLUSION_VALUE = "reconciled"

/**
 * The transfers outside [transferIds] that a RECONCILED relationship ties to one of them. Snapshot this
 * BEFORE deleting [transferIds]: the relationship rows cascade away with the transfers, but a partner's
 * EXCLUDED attribute does not — left alone, that leg would stay hidden with no partner to explain it.
 * Pass the result to [unexcludeOrphanedReconciledPartners] once the deletes are done.
 */
suspend fun reconciledPartnersOf(
    transferIds: Set<TransferId>,
    transferRelationshipRepository: TransferRelationshipReadRepository,
): Set<TransferId> {
    if (transferIds.isEmpty()) return emptySet()
    return transferRelationshipRepository
        .getByTransfers(transferIds)
        .filter { it.relationshipType.id.id == WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID }
        .flatMap { listOf(it.id1, it.id2) }
        .filterNot { it in transferIds }
        .toSet()
}

/**
 * Removes the reconcile exclusion from each of [partners] (from [reconciledPartnersOf]) that has no
 * RECONCILED relationship left at all — one reconciled against more than one deleted leg (unusual, but
 * possible) must stay hidden until every one of them is gone. [beforeWrite] runs only when there is
 * something to un-hide. Returns the ids un-hidden.
 */
suspend fun ImportEngine.unexcludeOrphanedReconciledPartners(
    partners: Set<TransferId>,
    transferRelationshipRepository: TransferRelationshipReadRepository,
    transactionRepository: TransactionReadRepository,
    beforeWrite: suspend () -> Unit = {},
): Set<TransferId> {
    if (partners.isEmpty()) return emptySet()
    val stillReconciled =
        transferRelationshipRepository
            .getByTransfers(partners)
            .filter { it.relationshipType.id.id == WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID }
            .flatMap { listOf(it.id1, it.id2) }
            .toSet()
    val toUnexclude = partners - stillReconciled
    if (toUnexclude.isEmpty()) return emptySet()
    val partnerTransfers = transactionRepository.getTransactionsByIds(toUnexclude)
    val updates =
        toUnexclude.mapNotNull { id ->
            val attr =
                partnerTransfers[id]?.attributes?.firstOrNull {
                    it.attributeType.id.id == WellKnownIds.EXCLUDED_ATTR_TYPE_ID && it.value == RECONCILED_EXCLUSION_VALUE
                } ?: return@mapNotNull null
            ImportTransfer(
                source = Source.System,
                operation = ImportOperation.UPDATE,
                existingId = id,
                deletedAttributeIds = setOf(attr.id),
            )
        }
    if (updates.isEmpty()) return emptySet()
    beforeWrite()
    import(ImportBatch(transfers = updates, dedupePolicy = DedupePolicy.None))
    return updates.mapNotNull { it.existingId }.toSet()
}
