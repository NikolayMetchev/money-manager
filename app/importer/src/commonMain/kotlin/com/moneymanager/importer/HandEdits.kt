package com.moneymanager.importer

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.observation.AttributeOverride
import com.moneymanager.domain.model.observation.MaterializedTransaction
import com.moneymanager.domain.model.observation.TransferField
import com.moneymanager.domain.repository.write.ObservationWriteRepository
import com.moneymanager.domain.repository.write.PersonOverrideWriteRepository
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.Materializer.column
import com.moneymanager.importengineapi.ObservationDecision
import kotlinx.coroutines.flow.first

/** What a person decides about a transaction: their edits, recorded as overrides, and their pins and splits. */
internal class HandEdits(
    private val observationRepository: ObservationWriteRepository,
    private val personOverrideRepository: PersonOverrideWriteRepository,
) {
    // What each transfer a person edits holds before the edit.
    suspend fun snapshot(updates: List<ImportTransfer>): Map<TransferId, MaterializedTransaction> {
        val observations = observationRepository
        return updates
            .filter { it.source == Source.Manual }
            .mapNotNull { intent -> requireNotNull(intent.existingId).let { id -> observations.getMaterialized(id)?.let { id to it } } }
            .toMap()
    }

    suspend fun record(before: Map<TransferId, MaterializedTransaction>) {
        val overrides = personOverrideRepository
        for ((id, was) in before) {
            val now = observationRepository.getMaterialized(id) ?: continue
            overrides.recordTransferEdit(id, was, now)
        }
    }

    suspend fun applyDecisions(decisions: List<ObservationDecision>) {
        val overrides = personOverrideRepository
        for (decision in decisions) {
            when (decision) {
                is ObservationDecision.Pin -> overrides.pin(decision.pin)
                is ObservationDecision.Unpin -> overrides.unpin(decision.observationId)
                is ObservationDecision.Split -> overrides.split(decision.split)
                is ObservationDecision.Unsplit -> overrides.unsplit(decision.split)
            }
        }
    }
}

/**
 * Records what a person's edit of a transfer changed as their overrides, so a later rebuild from the observations keeps
 * it: each field whose stored value moved, each attribute set or changed, and each attribute removed. What the edit left
 * as it was records nothing, so an untouched field still follows what the sources say.
 */
private suspend fun PersonOverrideWriteRepository.recordTransferEdit(
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
