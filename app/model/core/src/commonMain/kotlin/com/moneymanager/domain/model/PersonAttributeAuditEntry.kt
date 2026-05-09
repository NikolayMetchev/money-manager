package com.moneymanager.domain.model

data class PersonAttributeAuditEntry(
    val id: Long,
    val personId: PersonId,
    val revisionId: Long,
    val attributeType: AttributeType,
    val auditType: AuditType,
    val value: String,
)
