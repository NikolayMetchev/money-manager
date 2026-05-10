package com.moneymanager.domain.model

data class PersonAttributeAuditEntry(
    val id: Long,
    val auditTimestamp: kotlin.time.Instant,
    val personId: PersonId,
    val revisionId: Long,
    val attributeType: AttributeType,
    val auditType: AuditType,
    val value: String,
)
