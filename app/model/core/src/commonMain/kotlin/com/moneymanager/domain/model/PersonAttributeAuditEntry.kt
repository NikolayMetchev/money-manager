@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

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
    val auditTimestamp: Instant,
)
