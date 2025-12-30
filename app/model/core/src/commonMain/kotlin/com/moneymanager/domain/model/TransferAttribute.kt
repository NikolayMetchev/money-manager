package com.moneymanager.domain.model

data class TransferAttribute(
    val id: Long,
    val transactionId: TransferId,
    val attributeType: AttributeType,
    val value: String,
)
