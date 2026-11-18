package com.moneymanager.importengineapi

import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.observation.COPY_SLOT_PREFIX
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
    fun conduit(index: Int): String = "$CONDUIT_PREFIX$index"

    /** What every [conduit] slot starts with. */
    const val CONDUIT_PREFIX = "conduit/"

    /** The trade the record or group reports, or the conversion its own account settled the movement with. */
    const val TRADE = "trade"

    /** A placeholder for what only a person can say: a `manual-entry` trade standing in for an unseen leg. */
    const val MANUAL_ENTRY = "manual-entry"

    /**
     * What "Copy to Money Manager" books for the owner's [slot] (#1475): the reconciliation source's record says it too, on the
     * real accounts its wallets are linked to, so a re-import of the source updates or removes the copy with the row.
     */
    fun copy(slot: String): String = "$COPY_PREFIX$slot"

    /** What every [copy] slot starts with. */
    const val COPY_PREFIX = COPY_SLOT_PREFIX
}

/** Who an observation belongs to, before anything is written: no ids, only what the source itself says. */
sealed interface ObservationOwnerKey {
    /** One staged row or API item. */
    data class Record(
        val key: ImportRecordKey,
    ) : ObservationOwnerKey

    /**
     * The records a `LegGroupRule` joins into one output (a trade assembled from legs, across files too): [members] are
     * the records of its legs, which become the group's members.
     */
    data class Group(
        val key: ImportGroupKey,
        val members: Set<ImportRecordKey> = emptySet(),
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
