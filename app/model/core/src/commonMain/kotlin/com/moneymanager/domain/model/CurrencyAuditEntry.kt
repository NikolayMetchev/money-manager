@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

/**
 * Represents an audit entry for a currency (asset).
 * Captures the state of a currency at a specific point in time during an INSERT, UPDATE, or DELETE operation.
 *
 * @property auditId Unique identifier for this audit entry
 * @property auditTimestamp When this audit entry was created
 * @property auditType The type of operation that triggered this audit (INSERT, UPDATE, DELETE)
 * @property currencyId The ID of the currency that was audited
 * @property revisionId The revision of the currency at the time of the audit
 * @property code The currency code at the time of the audit
 * @property name The currency name at the time of the audit
 * @property scaleFactor The scale factor at the time of the audit
 */
data class CurrencyAuditEntry(
    val auditId: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val currencyId: CurrencyId,
    val revisionId: Long,
    val code: String,
    val name: String,
    val scaleFactor: Long,
)
