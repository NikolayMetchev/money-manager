package com.moneymanager.importengineapi

import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.observation.ImportGroupKey
import com.moneymanager.domain.model.observation.ImportRecordKey

/**
 * The output slots an owner can say something about. A slot name is derived from what the record says, never from
 * ids or file order, so one record always fills the same slots however often and in whatever order it is imported.
 */
object ObservationSlot {
    /** The movement the record reports itself. */
    const val MAIN = "main"

    /** The charge the record reports alongside its movement, paid into its own account's fee account. */
    const val FEE = "fee"

    /**
     * A pass-through chain's onward movement out of conduit [index] (outermost first): `conduit/0` is the first
     * conduit paying the next one, or the merchant.
     */
    fun conduit(index: Int): String = "conduit/$index"

    /** The trade the record or group reports, or the conversion its own account settled the movement with. */
    const val TRADE = "trade"

    /** A placeholder for what only a person can say: a `manual-entry` trade standing in for an unseen leg. */
    const val MANUAL_ENTRY = "manual-entry"
}

/** Who an observation belongs to, before anything is written: no ids, only what the source itself says. */
sealed interface ObservationOwnerKey {
    /** One staged row or API item. */
    data class Record(
        val key: ImportRecordKey,
    ) : ObservationOwnerKey

    /** The records a `LegGroupRule` joins into one output (a trade assembled from legs, across files too). */
    data class Group(
        val key: ImportGroupKey,
    ) : ObservationOwnerKey

    /**
     * A transfer no import record backs (a hand entry, a system or sample write): its batch's [source] and the
     * [rowKey] that keeps it apart from the batch's others.
     */
    data class Unrecorded(
        val source: Source,
        val rowKey: ImportRowKey,
    ) : ObservationOwnerKey
}

/** One observation's identity: what [owner] says about [slot]. Unique within a source, stable across runs. */
data class ObservationKey(
    val owner: ObservationOwnerKey,
    val slot: String,
)

/**
 * The import record behind [rowKey] in a batch from this source: a CSV row of the source's staged file, or an
 * item of the source's API session. Null for a source that stages nothing (hand entries, system writes).
 */
fun Source.recordKey(rowKey: ImportRowKey): ImportRecordKey? =
    when (this) {
        is Source.Csv -> {
            (rowKey as? ImportRowKey.CsvRow)?.let { ImportRecordKey.CsvRow(importId, it.rowIndex) }
        }

        is Source.Api -> {
            (rowKey as? ImportRowKey.ApiJsonPath)?.let { ImportRecordKey.ApiItem(sessionId, it.requestId, it.jsonPath) }
        }

        Source.Manual,
        Source.SampleGenerator,
        Source.System,
        Source.Merge,
        Source.Unmerge,
        Source.Reconciliation,
        Source.Reconciler,
        -> {
            null
        }
    }

/** The owner of this transfer's observations: its group when a `LegGroupRule` built it, else its own record. */
fun ImportTransfer.observationOwner(): ObservationOwnerKey {
    group?.let { return ObservationOwnerKey.Group(it) }
    val rowKey = requireNotNull(rowKey) { "An imported transfer needs a row key: \"$description\"" }
    return source.recordKey(rowKey)?.let(ObservationOwnerKey::Record) ?: ObservationOwnerKey.Unrecorded(source, rowKey)
}

/** The key of this transfer's own movement. */
fun ImportTransfer.observationKey(): ObservationKey = ObservationKey(observationOwner(), observationSlot)

/** The key of this trade's observation, or null for a trade no record or group backs (a hand entry, a deletion). */
fun ImportTradeIntent.observationKey(): ObservationKey? = owner?.let { ObservationKey(it, observationSlot) }
