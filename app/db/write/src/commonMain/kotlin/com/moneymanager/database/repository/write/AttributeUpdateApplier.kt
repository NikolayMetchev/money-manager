package com.moneymanager.database.repository.write

import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.NewAttribute

internal fun applyAttributeChangesInCreationMode(
    database: MoneyManagerDatabaseWrapper,
    deletedAttributeIds: Set<Long>,
    updatedAttributes: Map<Long, NewAttribute>,
    newAttributes: List<NewAttribute>,
    selectCurrentSlot: (Long) -> Pair<Long, String>?,
    deleteById: (Long) -> Unit,
    insertAttribute: (NewAttribute) -> Unit,
    insertAttributeForUpdatedType: (NewAttribute) -> Unit,
    updateValue: (value: String, id: Long) -> Unit,
) {
    database.beginCreationMode()
    try {
        deletedAttributeIds.forEach { id ->
            deleteById(id)
        }

        updatedAttributes.forEach { (id, attr) ->
            val currentSlot = selectCurrentSlot(id)
            // The (type, group) pair is the row's UNIQUE slot. Changing either moves the row to a
            // different slot, which an in-place UPDATE cannot express — it would keep the old group
            // (silently un-grouping or mis-grouping the attribute) and could collide with the row
            // already sitting in the target slot. Re-create instead.
            if (currentSlot != null && currentSlot != (attr.typeId.id to attr.groupKey)) {
                deleteById(id)
                insertAttributeForUpdatedType(attr)
            } else {
                updateValue(attr.value, id)
            }
        }

        newAttributes.forEach { attr ->
            insertAttribute(attr)
        }
    } finally {
        database.endCreationMode()
    }
}

internal fun updateEntityWithAttributes(
    database: MoneyManagerDatabaseWrapper,
    hasEntityChanges: Boolean,
    deletedAttributeIds: Set<Long>,
    updatedAttributes: Map<Long, NewAttribute>,
    newAttributes: List<NewAttribute>,
    updateEntity: () -> Unit,
    bumpRevisionOnly: () -> Unit,
    selectRevision: () -> Long,
    selectCurrentSlot: (Long) -> Pair<Long, String>?,
    deleteById: (Long) -> Unit,
    insertAttribute: (NewAttribute) -> Unit,
    updateValue: (value: String, id: Long) -> Unit,
): Long {
    val hasAttributeChanges =
        deletedAttributeIds.isNotEmpty() ||
            updatedAttributes.isNotEmpty() ||
            newAttributes.isNotEmpty()

    if (hasEntityChanges) {
        updateEntity()
    } else if (hasAttributeChanges) {
        bumpRevisionOnly()
    }

    if (hasAttributeChanges) {
        applyAttributeChangesInCreationMode(
            database = database,
            deletedAttributeIds = deletedAttributeIds,
            updatedAttributes = updatedAttributes,
            newAttributes = newAttributes,
            selectCurrentSlot = selectCurrentSlot,
            deleteById = deleteById,
            insertAttribute = insertAttribute,
            insertAttributeForUpdatedType = insertAttribute,
            updateValue = updateValue,
        )
    }

    return selectRevision()
}
