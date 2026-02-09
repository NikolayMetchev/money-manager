@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

/**
 * Represents an audit entry for a category.
 * Captures the state of a category at a specific point in time during an INSERT, UPDATE, or DELETE operation.
 *
 * @property id Unique identifier for this audit entry
 * @property auditTimestamp When this audit entry was created
 * @property auditType The type of operation that triggered this audit (INSERT, UPDATE, DELETE)
 * @property categoryId The ID of the category that was audited
 * @property revisionId The revision of the category at the time of the audit
 * @property name The name at the time of the audit
 * @property parentId The parent category ID at the time of the audit
 * @property parentName The parent category name at the time of the audit (for display purposes)
 * @property source The source/provenance of this change
 */
data class CategoryAuditEntry(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val categoryId: Long,
    val revisionId: Long,
    val name: String,
    val parentId: Long?,
    val parentName: String?,
    val source: EntitySource? = null,
)
