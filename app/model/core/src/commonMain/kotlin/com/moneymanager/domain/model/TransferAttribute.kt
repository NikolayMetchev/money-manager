package com.moneymanager.domain.model

data class TransferAttribute(
    val id: Long,
    val transactionId: TransferId,
    val attributeType: AttributeType,
    val value: String,
)

/**
 * An attribute value to be created (before it has an ID).
 * Used when creating new attributes for a transfer.
 */
data class NewAttribute(
    val typeId: AttributeTypeId,
    val value: String,
)
