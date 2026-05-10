package com.moneymanager.domain.model

import kotlin.time.Instant

data class PersonAttributeAuditEntry(
    val id: Long,
    val auditTimestamp: Instant,
    val personId: PersonId,
    val revisionId: Long,
    val attributeType: AttributeType,
    val auditType: AuditType,
    val value: String,
)
