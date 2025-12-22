@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

/**
 * Represents an audit entry for a transfer/transaction.
 * Captures the state of a transfer at a specific point in time during an INSERT, UPDATE, or DELETE operation.
 *
 * @property auditId Unique identifier for this audit entry
 * @property auditTimestamp When this audit entry was created
 * @property auditType The type of operation that triggered this audit (INSERT, UPDATE, DELETE)
 * @property transferId The ID of the transfer that was audited
 * @property revisionId The revision of the transfer at the time of the audit
 * @property timestamp The transaction timestamp at the time of the audit
 * @property description The transaction description at the time of the audit
 * @property sourceAccountId The source account ID at the time of the audit
 * @property targetAccountId The target account ID at the time of the audit
 * @property amount The monetary amount at the time of the audit (includes currency)
 * @property source The source/provenance of this audit entry (null if not tracked)
 */
data class TransferAuditEntry(
    val auditId: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val transferId: TransferId,
    val revisionId: Long,
    val timestamp: Instant,
    val description: String,
    val sourceAccountId: AccountId,
    val targetAccountId: AccountId,
    val amount: Money,
    val source: TransferSource? = null,
)

enum class AuditType {
    INSERT,
    UPDATE,
    DELETE,
}
