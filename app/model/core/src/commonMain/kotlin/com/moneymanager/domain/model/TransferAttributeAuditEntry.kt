package com.moneymanager.domain.model

/**
 * Represents an audit entry for a transfer attribute change.
 * Records what happened to an attribute at a specific transfer revision.
 *
 * Storage pattern (same as Transfer_Audit):
 * - INSERT: stores NEW value (attribute that was added)
 * - UPDATE: stores OLD value (value before the change)
 * - DELETE: stores OLD value (value that was removed)
 *
 * @property id Unique identifier for this audit entry
 * @property transactionId The ID of the transfer that owns this attribute
 * @property revisionId The transfer revision when this change occurred
 * @property attributeType The type of attribute that was changed
 * @property auditType The type of operation (INSERT, UPDATE, DELETE)
 * @property value The attribute value (NEW for INSERT, OLD for UPDATE/DELETE)
 */
data class TransferAttributeAuditEntry(
    val id: Long,
    val transactionId: TransferId,
    val revisionId: Long,
    val attributeType: AttributeType,
    val auditType: AuditType,
    val value: String,
)
