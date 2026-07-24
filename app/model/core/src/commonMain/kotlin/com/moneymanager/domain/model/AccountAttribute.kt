package com.moneymanager.domain.model

data class AccountAttribute(
    val id: Long,
    val accountId: AccountId,
    val attributeType: AttributeType,
    val value: String,
    val groupKey: String = "",
)
