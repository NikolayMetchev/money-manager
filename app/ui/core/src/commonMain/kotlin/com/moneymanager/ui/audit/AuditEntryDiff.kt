@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.audit

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.TransferAttribute
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
    val revisionId: Long,
    val timestamp: FieldChange<Instant>,
    val description: FieldChange<String>,
    val sourceAccountId: FieldChange<AccountId>,
    val targetAccountId: FieldChange<AccountId>,
    val amount: FieldChange<Money>,
    val source: TransferSource? = null,
    val attributeChanges: List<AttributeChange> = emptyList(),
) {
    /**
     * Returns true if any field changed (only meaningful for UPDATE entries).
     */
    val hasChanges: Boolean
        get() =
            listOf(timestamp, description, sourceAccountId, targetAccountId, amount)
                .any { it is FieldChange.Changed } || attributeChanges.isNotEmpty()
}

/**
 * Represents a change to a single attribute.
 */
sealed class AttributeChange {
    abstract val attributeTypeName: String

    /** Attribute was added with this value. */
    data class Added(
        override val attributeTypeName: String,
        val value: String,
    ) : AttributeChange()

    /** Attribute was removed with this value. */
    data class Removed(
        override val attributeTypeName: String,
        val value: String,
    ) : AttributeChange()

    /** Attribute value changed. */
    data class Changed(
        override val attributeTypeName: String,
        val oldValue: String,
        val newValue: String,
    ) : AttributeChange()

    /** Attribute value stayed the same. */
    data class Unchanged(
        override val attributeTypeName: String,
        val value: String,
    ) : AttributeChange()
}

/**
 * Computes the changes between two sets of attributes.
 */
fun computeAttributeChanges(
    oldAttributes: List<TransferAttribute>,
    newAttributes: List<TransferAttribute>,
): List<AttributeChange> {
    val oldByType = oldAttributes.associateBy { it.attributeType.name }
    val newByType = newAttributes.associateBy { it.attributeType.name }

    val allTypeNames = (oldByType.keys + newByType.keys).sorted()

    return allTypeNames.mapNotNull { typeName ->
        val old = oldByType[typeName]
        val new = newByType[typeName]

        when {
            old == null && new != null -> AttributeChange.Added(typeName, new.value)
            old != null && new == null -> AttributeChange.Removed(typeName, old.value)
            old != null && new != null && old.value != new.value ->
                AttributeChange.Changed(typeName, old.value, new.value)
            old != null && new != null -> AttributeChange.Unchanged(typeName, new.value)
            else -> null
        }
    }
}
