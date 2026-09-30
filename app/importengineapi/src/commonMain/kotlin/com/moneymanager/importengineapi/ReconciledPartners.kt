package com.moneymanager.importengineapi

import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository

/** Value an internal-transfer reconcile's exclusion attribute always carries (see `ImportDeduper`). */
private const val RECONCILED_EXCLUSION_VALUE = "reconciled"

/**
 * The UPDATEs that lift the reconcile exclusion from every transfer outside [deletedIds] whose RECONCILED
 * relationships ALL point into [deletedIds] — once those are deleted the relationship rows cascade away,
 * but the partner's EXCLUDED attribute would not, leaving that leg hidden with nothing to explain it. A
 * partner still reconciled against a surviving transfer (unusual, but possible) keeps its exclusion.
 *
 * Computed BEFORE the deletes so the caller can put these updates in the same batch as them: the engine
 * applies updates before deletes, so a failure can never leave the deletes done and the un-hide lost.
 */
suspend fun reconciledPartnerUnhideUpdates(
    deletedIds: Set<TransferId>,
    transferRelationshipRepository: TransferRelationshipReadRepository,
    transactionRepository: TransactionReadRepository,
): List<ImportTransfer> {
    if (deletedIds.isEmpty()) return emptyList()
    val partners =
        reconciledRelationships(deletedIds, transferRelationshipRepository)
            .flatMap { listOf(it.id1, it.id2) }
            .filterNot { it in deletedIds }
            .toSet()
    if (partners.isEmpty()) return emptyList()
    val orphaned =
        reconciledRelationships(partners, transferRelationshipRepository)
            .flatMap { listOf(it.id1 to it.id2, it.id2 to it.id1) }
            .filter { (partner, _) -> partner in partners }
            .groupBy({ it.first }, { it.second })
            .filter { (_, counterparts) -> counterparts.all { it in deletedIds } }
            .keys
    if (orphaned.isEmpty()) return emptyList()
    val partnerTransfers = transactionRepository.getTransactionsByIds(orphaned)
    return orphaned.mapNotNull { id ->
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
}

private suspend fun reconciledRelationships(
    ids: Set<TransferId>,
    transferRelationshipRepository: TransferRelationshipReadRepository,
) = transferRelationshipRepository
    .getByTransfers(ids)
    .filter { it.relationshipType.id.id == WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID }
