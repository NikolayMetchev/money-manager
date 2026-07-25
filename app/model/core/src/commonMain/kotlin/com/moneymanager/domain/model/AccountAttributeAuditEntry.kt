package com.moneymanager.domain.model

import kotlin.time.Instant

/**
 * Represents an audit entry for an account attribute change.
 * Records what happened to an attribute at a specific account revision.
 *
 * Storage pattern (same as TransferAttributeAuditEntry):
 * - INSERT: stores NEW value (attribute that was added)
 * - UPDATE: stores OLD value (value before the change)
 * - DELETE: stores OLD value (value that was removed)
 *
 * @property id Unique identifier for this audit entry
 * @property accountId The ID of the account that owns this attribute
 * @property revisionId The account revision when this change occurred
 * @property attributeType The type of attribute that was changed
 * @property auditType The type of operation (INSERT, UPDATE, DELETE)
 * @property value The attribute value (NEW for INSERT, OLD for UPDATE/DELETE)
 */
data class AccountAttributeAuditEntry(
    val id: Long,
    val auditTimestamp: Instant,
    val accountId: AccountId,
    val revisionId: Long,
    val attributeType: AttributeType,
    val auditType: AuditType,
    val value: String,
    val groupKey: String = "",
)
