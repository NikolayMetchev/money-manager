@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.audit

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.TransferSource
import kotlin.time.Instant

/**
 * Represents an audit entry with field-level diff information.
 * Instead of storing raw values, each field is wrapped in [FieldChange]
 * to indicate whether it was created, changed, unchanged, or deleted.
 */
data class AuditEntryDiff(
    val auditId: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val transferId: TransferId,
    val timestamp: FieldChange<Instant>,
    val description: FieldChange<String>,
    val sourceAccountId: FieldChange<AccountId>,
    val targetAccountId: FieldChange<AccountId>,
    val amount: FieldChange<Money>,
    val source: TransferSource? = null,
) {
    /**
     * Returns true if any field changed (only meaningful for UPDATE entries).
     */
    val hasChanges: Boolean
        get() =
            listOf(timestamp, description, sourceAccountId, targetAccountId, amount)
                .any { it is FieldChange.Changed }
}
