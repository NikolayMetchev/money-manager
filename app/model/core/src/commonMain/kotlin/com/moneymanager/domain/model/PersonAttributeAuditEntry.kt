package com.moneymanager.domain.model

/**
 * Represents an audit entry for a person attribute change.
 * Records what happened to an attribute at a specific person revision.
 */
data class PersonAttributeAuditEntry(
    val id: Long,
    val personId: PersonId,
    val revisionId: Long,
    val attributeType: AttributeType,
    val auditType: AuditType,
    val value: String,
)
