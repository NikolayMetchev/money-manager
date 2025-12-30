@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.audit

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferAuditEntry
import kotlin.time.Instant

/**
 * Computes the diff for an audit entry.
 *
 * For UPDATE entries, the audit stores OLD values (before the update).
 * To show "changed from X to Y", we need:
 * - X = OLD values from this entry
 * - Y = NEW values (from nextEntry or currentTransfer)
 *
 * Attribute changes are stored directly in TransferAttributeAudit and attached
 * to each TransferAuditEntry. Each attribute audit entry indicates:
 * - INSERT: attribute was added (value = new value)
 * - UPDATE: attribute was changed (value = old value)
 * - DELETE: attribute was removed (value = old value)
 *
 * @param entry The audit entry
 * @param newValuesForUpdate For UPDATE entries: the values AFTER the update
 *        (from the next audit entry or current transfer). Ignored for INSERT/DELETE.
 * @return An [AuditEntryDiff] with field-level change information
 */
fun computeAuditDiff(
    entry: TransferAuditEntry,
    newValuesForUpdate: UpdateNewValues?,
): AuditEntryDiff {
    // Convert attribute audit entries to AttributeChange display model
    val attributeChanges =
        entry.attributeChanges.map { attrAudit ->
            when (attrAudit.auditType) {
                AuditType.INSERT -> AttributeChange.Added(attrAudit.attributeType.name, attrAudit.value)
                AuditType.UPDATE -> AttributeChange.ModifiedFrom(attrAudit.attributeType.name, attrAudit.value)
                AuditType.DELETE -> AttributeChange.Removed(attrAudit.attributeType.name, attrAudit.value)
            }
        }

    return when (entry.auditType) {
        AuditType.INSERT ->
            AuditEntryDiff(
                auditId = entry.auditId,
                auditTimestamp = entry.auditTimestamp,
                auditType = entry.auditType,
                transferId = entry.transferId,
                revisionId = entry.revisionId,
                timestamp = FieldChange.Created(entry.timestamp),
                description = FieldChange.Created(entry.description),
                sourceAccountId = FieldChange.Created(entry.sourceAccountId),
                targetAccountId = FieldChange.Created(entry.targetAccountId),
                amount = FieldChange.Created(entry.amount),
                source = entry.source,
                attributeChanges = attributeChanges,
            )
        AuditType.DELETE ->
            AuditEntryDiff(
                auditId = entry.auditId,
                auditTimestamp = entry.auditTimestamp,
                auditType = entry.auditType,
                transferId = entry.transferId,
                revisionId = entry.revisionId,
                timestamp = FieldChange.Deleted(entry.timestamp),
                description = FieldChange.Deleted(entry.description),
                sourceAccountId = FieldChange.Deleted(entry.sourceAccountId),
                targetAccountId = FieldChange.Deleted(entry.targetAccountId),
                amount = FieldChange.Deleted(entry.amount),
                source = entry.source,
                attributeChanges = attributeChanges,
            )
        AuditType.UPDATE -> {
            requireNotNull(newValuesForUpdate) { "UPDATE entry must have new values to compare against" }
            AuditEntryDiff(
                auditId = entry.auditId,
                auditTimestamp = entry.auditTimestamp,
                auditType = entry.auditType,
                transferId = entry.transferId,
                revisionId = entry.revisionId,
                timestamp = computeFieldChange(entry.timestamp, newValuesForUpdate.timestamp),
                description = computeFieldChange(entry.description, newValuesForUpdate.description),
                sourceAccountId = computeFieldChange(entry.sourceAccountId, newValuesForUpdate.sourceAccountId),
                targetAccountId = computeFieldChange(entry.targetAccountId, newValuesForUpdate.targetAccountId),
                amount = computeFieldChange(entry.amount, newValuesForUpdate.amount),
                source = entry.source,
                attributeChanges = attributeChanges,
            )
        }
    }
}

/**
 * Holds the NEW values for an UPDATE comparison.
 * These are the values the transfer changed TO after the update.
 */
data class UpdateNewValues(
    val timestamp: Instant,
    val description: String,
    val sourceAccountId: AccountId,
    val targetAccountId: AccountId,
    val amount: Money,
) {
    companion object {
        fun fromTransfer(transfer: Transfer): UpdateNewValues =
            UpdateNewValues(
                timestamp = transfer.timestamp,
                description = transfer.description,
                sourceAccountId = transfer.sourceAccountId,
                targetAccountId = transfer.targetAccountId,
                amount = transfer.amount,
            )

        fun fromAuditEntry(entry: TransferAuditEntry): UpdateNewValues =
            UpdateNewValues(
                timestamp = entry.timestamp,
                description = entry.description,
                sourceAccountId = entry.sourceAccountId,
                targetAccountId = entry.targetAccountId,
                amount = entry.amount,
            )
    }
}

private fun <T> computeFieldChange(
    old: T,
    new: T,
): FieldChange<T> = if (old == new) FieldChange.Unchanged(new) else FieldChange.Changed(old, new)
