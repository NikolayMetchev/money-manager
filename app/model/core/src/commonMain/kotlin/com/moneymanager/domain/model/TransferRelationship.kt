package com.moneymanager.domain.model

/**
 * A typed relationship between two transfers.
 *   [id1] = the primary/owning transfer (e.g. the reconciled duplicate, or the main transaction)
 *   [id2] = the related transfer        (e.g. the original it mirrors, or the fee transfer)
 */
data class TransferRelationship(
    val id1: TransferId,
    val id2: TransferId,
    val relationshipType: RelationshipType,
)

/**
 * A relationship to be created during import, before the owning transfer's id exists.
 * The owning transfer becomes id1; [relatedTransferId] becomes id2.
 */
data class NewRelationship(
    val relatedTransferId: TransferId,
    val typeId: RelationshipTypeId,
)
