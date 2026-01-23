@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

/**
 * Represents an audit entry for a person-account ownership relationship.
 * Captures the state of an ownership at a specific point in time during an INSERT, UPDATE, or DELETE operation.
 *
 * @property auditId Unique identifier for this audit entry
 * @property auditTimestamp When this audit entry was created
 * @property auditType The type of operation that triggered this audit (INSERT, UPDATE, DELETE)
 * @property ownershipId The ID of the ownership that was audited
 * @property revisionId The revision of the ownership at the time of the audit
 * @property personId The person ID at the time of the audit
 * @property accountId The account ID at the time of the audit
 * @property personFullName The person's full name at the time of the audit (null if person deleted)
 * @property accountName The account name at the time of the audit (null if account deleted)
 */
data class PersonAccountOwnershipAuditEntry(
    val auditId: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val ownershipId: Long,
    val revisionId: Long,
    val personId: PersonId,
    val accountId: AccountId,
    val personFullName: String?,
    val accountName: String?,
)
