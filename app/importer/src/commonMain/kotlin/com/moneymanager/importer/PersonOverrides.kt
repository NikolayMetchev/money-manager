package com.moneymanager.importer

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.observation.AttributeOverride
import com.moneymanager.domain.model.observation.MaterializedTransaction
import com.moneymanager.domain.model.observation.TransferField
import com.moneymanager.domain.repository.write.PersonOverrideWriteRepository
import com.moneymanager.importengineapi.Materializer.column

/**
 * Records what a person's edit of a transfer changed as their overrides, so a later rebuild from the observations keeps
 * it: each field whose stored value moved, each attribute set or changed, and each attribute removed. What the edit left
 * as it was records nothing, so an untouched field still follows what the sources say.
 */
internal suspend fun PersonOverrideWriteRepository.recordTransferEdit(
    transferId: TransferId,
    before: MaterializedTransaction,
    after: MaterializedTransaction,
) {
    for (field in TransferField.entries) {
        val value = after.values.column(field) ?: continue
        if (value != before.values.column(field)) setTransferOverride(transferId, field, value)
    }
    val was = before.attributes.associate { (it.typeId to it.groupKey) to it.value }
    val now = after.attributes.associate { (it.typeId to it.groupKey) to it.value }
    for ((key, value) in now) {
        if (was[key] != value) setTransferAttributeOverride(transferId, AttributeOverride.Set(key.first, key.second, value))
    }
    for (key in was.keys - now.keys) {
        setTransferAttributeOverride(transferId, AttributeOverride.Removed(key.first, key.second))
    }
}

internal fun AttributeTypeId.tradeOverride(value: String?): AttributeOverride =
    if (value == null) AttributeOverride.Removed(this, "") else AttributeOverride.Set(this, "", value)
