package com.moneymanager.domain.model

data class PersonAttribute(
    val id: Long,
    val personId: PersonId,
    val attributeType: AttributeType,
    val value: String,
)
