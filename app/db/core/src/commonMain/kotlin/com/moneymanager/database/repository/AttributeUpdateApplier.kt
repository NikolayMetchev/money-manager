package com.moneymanager.database.repository

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.NewAttribute

internal fun applyAttributeChangesInCreationMode(
    database: MoneyManagerDatabaseWrapper,
    deletedAttributeIds: Set<Long>,
    updatedAttributes: Map<Long, NewAttribute>,
    newAttributes: List<NewAttribute>,
    selectCurrentTypeId: (Long) -> Long?,
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
            val currentTypeId = selectCurrentTypeId(id)
            if (currentTypeId != null && currentTypeId != attr.typeId.id) {
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
