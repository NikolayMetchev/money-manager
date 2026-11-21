package com.moneymanager.importer

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransactionKind
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.observation.ImportGroupId
import com.moneymanager.domain.model.observation.ImportGroupKey
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.observation.Observation
import com.moneymanager.domain.model.observation.ObservationBooking
import com.moneymanager.domain.model.observation.ObservationChange
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.VacantObservation
import com.moneymanager.domain.repository.write.ImportRecordWriteRepository
import com.moneymanager.domain.repository.write.ObservationWriteRepository
import com.moneymanager.domain.repository.write.RecordObservationInsert
import com.moneymanager.importengineapi.ExpandedTransfer
import com.moneymanager.importengineapi.ObservationKey
import com.moneymanager.importengineapi.ObservationOwnerKey
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.importengineapi.observationContent
import kotlinx.coroutines.flow.first

/** Observations are written this many per database transaction. */
internal const val OBSERVATION_CHUNK_ROWS = 500

/** The content hash of an API item's import record until the API sync (#1429) hashes what each item says. */
internal const val RECORD_UNHASHED = "unhashed"

internal data class SourceBooking(
    val key: ObservationKey?,
    val provenance: Source,
    val transactionId: TransactionId,
    val content: () -> ObservationContent,
)

/** Records what each source says and lets a re-import take back its reserved transaction IDs. */
internal class SourceObservations(
    private val observations: ObservationWriteRepository,
    private val records: ImportRecordWriteRepository,
    private val unidentifiedCounterpartyTypeId: AttributeTypeId?,
) {
    // Each staged file's or API session's records, read once per batch: the container they belong to, then their keys.
    private val containerRecords = mutableMapOf<Any, MutableMap<ImportRecordKey, ImportRecordId>>()
    private val groups = mutableMapOf<ImportGroupKey, ImportGroupId>()

    // Each file or session reads only its own record slots. Groups may span files and have their own pool.
    private val vacantRecords = mutableMapOf<Any, MutableMap<Pair<ObservationOwner, String>, VacantObservation>>()
    private var vacantGroups: MutableMap<Pair<ObservationOwner, String>, VacantObservation>? = null
    private val claimedIds = mutableSetOf<TransactionId>()

    // The kept transactions this run took back, which the materializer brings to everything their observations say.
    val claimed: Set<TransactionId> get() = claimedIds.toSet()

    /**
     * The id a re-run reserved for [key]'s slot, when its record said it before and nothing took it back yet: a new
     * [kind] booking for it goes in under that id, so what a person decided about it still applies. Each id goes once.
     */
    suspend fun reuse(
        key: ObservationKey?,
        kind: TransactionKind,
    ): TransactionId? {
        val ownerKey = key?.owner ?: return null
        val pool: MutableMap<Pair<ObservationOwner, String>, VacantObservation>
        val owner =
            when (ownerKey) {
                is ObservationOwnerKey.Record -> {
                    val recordKey = ownerKey.key
                    val known = recordsOf(recordKey)
                    val id = known[recordKey] ?: return null
                    pool =
                        vacantRecords.getOrPut(recordKey.containerKey()) {
                            observations.getVacantReservedRecords(known.values).associateByTo(mutableMapOf()) { it.owner to it.slot }
                        }
                    ObservationOwner.Record(id)
                }

                is ObservationOwnerKey.Group -> {
                    pool = vacantGroups ?: observations
                        .getVacantReservedGroups()
                        .associateByTo(mutableMapOf()) { it.owner to it.slot }
                        .also { vacantGroups = it }
                    if (pool.isEmpty()) return null
                    ObservationOwner.Group(groups.getOrPut(ownerKey.key) { records.getOrCreateGroup(ownerKey.key) })
                }

                else -> {
                    return null
                }
            }
        val reserved = pool[owner to key.slot] ?: return null
        val id = reserved.transactionId
        if ((id is TransferId) != (kind == TransactionKind.TRANSFER)) return null
        pool.remove(owner to key.slot)
        // Several slots can observe the same transaction, and batched writes claim IDs before inserting any rows.
        return id.takeIf { claimedIds.add(it) }
    }

    // Reserved record slots on transactions a re-run kept because other records still observe them (#1510).
    private var sharedRecords: Map<Pair<ObservationOwner, String>, TransactionId>? = null

    /**
     * The transaction [key]'s record observed before that other records observe too, when a re-run reserved its slot: a
     * [kind] booking for it joins that transaction rather than booking a twin the match sync would fold back into it.
     */
    suspend fun joined(
        key: ObservationKey?,
        kind: TransactionKind,
    ): TransactionId? {
        val recordKey = (key?.owner as? ObservationOwnerKey.Record)?.key ?: return null
        val recordId = recordsOf(recordKey)[recordKey] ?: return null
        val shared =
            sharedRecords ?: run {
                val records = observations.getReserved().filter { it.owner is ObservationOwner.Record }
                val vacant = observations.getVacantTransactionIds(records.map { it.transactionId })
                records
                    .filter { it.transactionId !in vacant }
                    .associate { (it.owner to it.slot) to it.transactionId }
                    .also { sharedRecords = it }
            }
        val id = shared[ObservationOwner.Record(recordId) to key.slot] ?: return null
        if ((id is TransferId) != (kind == TransactionKind.TRANSFER)) return null
        claimedIds += id
        return id
    }

    fun transferContent(
        leg: ExpandedTransfer,
        approximateTime: Boolean,
    ): ObservationContent = leg.observationContent(approximateTime, unidentifiedCounterpartyTypeId)

    suspend fun recordTransfers(legs: List<TransferObservation>): Set<TransactionId> =
        recordAll(
            legs.map { (leg, id, approximate) ->
                SourceBooking(leg.key, leg.provenance, id) { transferContent(leg, approximate) }
            },
        )

    suspend fun recordAll(bookings: List<SourceBooking>): Set<TransactionId> {
        val loadedRecords =
            bookings.mapNotNullTo(mutableSetOf()) { booking ->
                (booking.key?.owner as? ObservationOwnerKey.Record)?.key?.let { knownRecord(it) }
            }
        val stored = observations.getObservationsOfRecords(loadedRecords).associateBy { it.owner to it.slot }
        val reserved = observations.getReservedIds(stored.values.map { it.id })
        val changes = mutableListOf<ObservationChange>()
        val reclaimed = mutableListOf<ObservationId>()
        val booked = mutableMapOf<Pair<ObservationOwner, String>, TransactionId>()
        val pending = linkedMapOf<Pair<ImportRecordKey, String>, RecordObservationInsert>()
        val observed = mutableSetOf<TransactionId>()

        suspend fun flushFresh() {
            if (pending.isEmpty()) return
            val inserted = observations.insertRecordObservations(RECORD_UNHASHED, pending.values.toList())
            for ((key, id) in inserted) recordsOf(key)[key] = id
            pending.clear()
        }

        suspend fun flushChanges() {
            if (changes.isEmpty() && reclaimed.isEmpty()) return
            observations.applyObservationChanges(emptyList(), changes, emptyList(), reclaimed = reclaimed)
            changes.clear()
            reclaimed.clear()
        }

        for (booking in bookings) {
            val key = (booking.key?.owner as? ObservationOwnerKey.Record)?.key
            val recordId = key?.let { knownRecord(it) }
            val slot = booking.key?.slot ?: ObservationSlot.TRADE
            val id = booking.transactionId
            when {
                key != null && recordId == null -> {
                    flushChanges()
                    val insert = pending.getOrPut(key to slot) { RecordObservationInsert(key, slot, id, booking.content()) }
                    observed += insert.transactionId
                    if (pending.size >= OBSERVATION_CHUNK_ROWS) flushFresh()
                }

                recordId != null && recordId in loadedRecords -> {
                    flushFresh()
                    val owner = ObservationOwner.Record(recordId)
                    val ownerSlot = owner to slot
                    observed +=
                        booked.getOrPut(ownerSlot) {
                            val existing = stored[ownerSlot]
                            if (existing != null && existing.id !in reserved) return@getOrPut existing.transactionId
                            val content = booking.content()
                            val target = if (existing == null) id else reclaimTarget(existing, id)
                            if (existing != null) reclaimed += existing.id
                            if (existing == null || existing.content != content || existing.transactionId != target) {
                                changes += ObservationChange(owner, slot, existing, ObservationBooking(existing?.id, target, content))
                            }
                            target
                        }
                    if (maxOf(changes.size, reclaimed.size) >= OBSERVATION_CHUNK_ROWS) flushChanges()
                }

                else -> {
                    // Groups and repeated slots of newly created records need to see the earlier writes.
                    flushFresh()
                    flushChanges()
                    // A source that keeps no observations (the sample generator) says nothing: its transaction isn't
                    // observed, and rebuilding it from no observation would delete it.
                    record(booking.key, booking.provenance, id, booking.content)?.let { observed += it }
                }
            }
        }
        flushFresh()
        flushChanges()
        return observed
    }

    // A duplicate can say its own still-vacant id again, unless another slot of this run already took it back. Matching
    // later decides whether the two are one.
    private suspend fun reclaimTarget(
        existing: Observation,
        target: TransactionId,
    ): TransactionId = existing.transactionId.takeIf { it != target && isVacant(it) && claimedIds.add(it) } ?: target

    // Vacant: every observation of it is reserved, so nothing but this re-run's records says it any more (#1510).
    private suspend fun isVacant(id: TransactionId): Boolean {
        val all = observations.getObservationsOfTransaction(id).map { it.id }
        return observations.getReservedIds(all).size == all.size
    }

    /**
     * Records [content] for [key] (null: a trade no record backs) on [transactionId], when [provenance] says whose, and
     * returns the transaction the observation is on (null when nothing was recorded).
     */
    suspend fun record(
        key: ObservationKey?,
        provenance: Source,
        transactionId: TransactionId,
        content: () -> ObservationContent,
    ): TransactionId? {
        val slot = key?.slot ?: ObservationSlot.TRADE
        val (owner, fresh) = owner(key?.owner, provenance, transactionId) ?: return null
        val existing = if (fresh) null else observations.getObservationsOf(owner).firstOrNull { it.slot == slot }
        if (existing != null) {
            // A slot its file's re-run reserved (#1430) is said again: it takes what the record says now, where it says it.
            if (observations.isReserved(existing.id)) {
                val now = content()
                if (existing.content != now) observations.updateContent(existing.id, now)
                // Said as a duplicate of another transaction while its own stands vacant, it takes its own back (#1429): the
                // match sync decides whether the two are one, as a fresh import has it, and a person's work stays on its id.
                val target = reclaimTarget(existing, transactionId)
                if (existing.transactionId.id != target.id) observations.assignTransaction(existing.id, target)
                observations.unreserve(existing.id)
                return target
            }
            // Otherwise a record seen before already says this slot (a trade its re-import found again): it keeps what it said.
            return existing.transactionId
        }
        observations.insertObservation(owner, slot, transactionId, content())
        return transactionId
    }

    /**
     * A trade booking that found [transactionId] standing rather than creating it: when its record's re-run reserved the
     * slot on that very trade (a person's work kept it standing, #1430), the record takes the observation back, or the
     * settle drops it and the trade is left unobserved. A trade another source booked stays that source's alone.
     */
    suspend fun reclaim(
        key: ObservationKey?,
        transactionId: TransactionId,
        content: () -> ObservationContent,
    ) {
        val owner =
            when (val ownerKey = key?.owner) {
                is ObservationOwnerKey.Record -> knownRecord(ownerKey.key)?.let { ObservationOwner.Record(it) }
                is ObservationOwnerKey.Group -> group(ownerKey).first
                else -> null
            } ?: return
        val existing = observations.getObservationsOf(owner).firstOrNull { it.slot == key?.slot } ?: return
        if (existing.transactionId.id != transactionId.id || !observations.isReserved(existing.id)) return
        val now = content()
        if (existing.content != now) observations.updateContent(existing.id, now)
        observations.unreserve(existing.id)
    }

    /** Whether [key]'s record said its slot before and its re-run reserved it (#1430). */
    suspend fun isReserved(key: ObservationKey): Boolean {
        val recordKey = (key.owner as? ObservationOwnerKey.Record)?.key ?: return false
        val owner = ObservationOwner.Record(knownRecord(recordKey) ?: return false)
        return observations.getObservationsOf(owner).any { it.slot == key.slot && observations.isReserved(it.id) }
    }

    /**
     * The owner, and whether it is new (so says nothing yet); null for a booking no API item, person or reconciliation copy
     * made. A person's entry and a reconciliation copy are owned by their creation's provenance row.
     */
    private suspend fun owner(
        key: ObservationOwnerKey?,
        provenance: Source,
        transactionId: TransactionId,
    ): Pair<ObservationOwner, Boolean>? {
        val recordKey = (key as? ObservationOwnerKey.Record)?.key
        return when {
            recordKey != null -> {
                record(recordKey)
            }

            key is ObservationOwnerKey.Group -> {
                group(key)
            }

            provenance == Source.Manual &&
                (key == null || key is ObservationOwnerKey.Unrecorded) -> {
                observations.getCreationSourceId(transactionId)?.let { ObservationOwner.Manual(it) to true }
            }

            else -> {
                null
            }
        }
    }

    // A staged row's record is normally written once its run completes (with its content hash), so one met first here gets
    // a hash that matches nothing, which that write then replaces: until it does, the file never looks unchanged.
    private suspend fun record(key: ImportRecordKey): Pair<ObservationOwner, Boolean> {
        val known = recordsOf(key)
        known[key]?.let { return ObservationOwner.Record(it) to false }
        val id = records.insertRecord(key, RECORD_UNHASHED)
        known[key] = id
        return ObservationOwner.Record(id) to true
    }

    /**
     * [key]'s import record, written now if this is the first the database hears of it. Asked after the batch's
     * observations, which can write records this cache never saw (a materialized observation mutation), so a miss reads
     * the container's records again before writing one.
     */
    suspend fun recordId(key: ImportRecordKey): ImportRecordId {
        if (knownRecord(key) == null) containerRecords.remove(key.containerKey())
        return (record(key).first as ObservationOwner.Record).recordId
    }

    private suspend fun knownRecord(key: ImportRecordKey): ImportRecordId? = recordsOf(key)[key]

    private suspend fun recordsOf(key: ImportRecordKey): MutableMap<ImportRecordKey, ImportRecordId> =
        containerRecords.getOrPut(key.containerKey()) {
            val existing =
                when (key) {
                    is ImportRecordKey.ApiItem -> records.getApiSessionRecords(key.sessionId)
                    is ImportRecordKey.CsvRow -> records.getCsvImportRecords(key.csvImportId)
                }
            existing.associateTo(mutableMapOf()) { it.key to it.id }
        }

    private fun ImportRecordKey.containerKey(): Any =
        when (this) {
            is ImportRecordKey.ApiItem -> sessionId
            is ImportRecordKey.CsvRow -> csvImportId
        }

    // A group outlives each of its runs (its legs may come from several files), so it may already say a slot. Each run
    // names every leg it joined, so its members are those of the latest run.
    private suspend fun group(key: ObservationOwnerKey.Group): Pair<ObservationOwner, Boolean> {
        val id = groups.getOrPut(key.key) { records.getOrCreateGroup(key.key) }
        if (key.members.isNotEmpty()) {
            val members = key.members.mapNotNullTo(mutableSetOf()) { (record(it).first as? ObservationOwner.Record)?.recordId }
            records.setGroupMembers(id, members)
        }
        return ObservationOwner.Group(id) to false
    }
}

internal data class TransferObservation(
    val leg: ExpandedTransfer,
    val id: TransferId,
    val approximate: Boolean,
)
