@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

/**
 * Represents an audit entry for an account.
 * Captures the state of an account at a specific point in time during an INSERT, UPDATE, or DELETE operation.
 *
 * @property auditId Unique identifier for this audit entry
 * @property auditTimestamp When this audit entry was created
 * @property auditType The type of operation that triggered this audit (INSERT, UPDATE, DELETE)
 * @property accountId The ID of the account that was audited
 * @property revisionId The revision of the account at the time of the audit
 * @property name The account name at the time of the audit
 * @property openingDate The account opening date at the time of the audit
 * @property categoryId The category ID at the time of the audit
 * @property categoryName The category name at the time of the audit (null if uncategorized or category deleted)
 */
data class AccountAuditEntry(
    val auditId: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val accountId: AccountId,
    val revisionId: Long,
    val name: String,
    val openingDate: Instant,
    val categoryId: Long,
    val categoryName: String?,
)
