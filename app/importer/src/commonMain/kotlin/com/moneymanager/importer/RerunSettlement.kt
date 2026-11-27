package com.moneymanager.importer

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.observation.ObservationChange
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.repository.write.ObservationWriteRepository
import com.moneymanager.domain.repository.write.PersonOverrideWriteRepository
import com.moneymanager.domain.repository.write.RelationshipWriteRepository
import com.moneymanager.domain.repository.write.TradeWriteRepository
import com.moneymanager.domain.repository.write.TransactionWriteRepository
import com.moneymanager.importengineapi.ApiSessionMutation
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.MaterializationReport
import com.moneymanager.importengineapi.MaterializationRequest
import com.moneymanager.importengineapi.Materializer
import com.moneymanager.importengineapi.ObservationMutation
import com.moneymanager.importengineapi.ReservedTransactions
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Transfers and trades are deleted this many per database transaction, with a progress update after each:
 * a commit per row waits on the disk every time (synchronous=FULL), which made a re-import's delete phase
 * ~8x slower than its deletes.
 */
internal const val DELETE_CHUNK_ROWS = 500
internal const val SETTLE_STEPS = 5
internal const val DROP_STEP = 3

/**
 * A re-run's reservations (#1430, #1510): what the transactions its files and sessions booked keep while their records are
 * said again, and the settle once they have been.
 */
internal class RerunSettlement(
    private val observationRepository: ObservationWriteRepository,
    private val personOverrideRepository: PersonOverrideWriteRepository,
    private val relationshipRepository: RelationshipWriteRepository,
    private val transactionRepository: TransactionWriteRepository,
    private val tradeRepository: TradeWriteRepository,
    private val copier: ReconciliationCopier,
    private val placeholderTypeId: () -> AttributeTypeId?,
    private val materialize: suspend (List<ObservationMutation>, MaterializationRequest) -> MaterializationReport,
) {
    /**
     * What a [CsvImportMutation.Reserve] or an [ApiSessionMutation.Reserve] keeps (#1430, #1429). Of [vacating] (the deletes no other source still observes), each a
     * reserved observation is on keeps its observations and its row (#1510), vacant until the re-run takes it back. A
     * reserved observation on a transaction that keeps its row (one the file shared with another source) stays reserved on
     * it too, so the record says it again there and a person's edits of it hold (#1432): the settle deletes it if the record
     * doesn't. Ids a previous, stopped re-run left vacant stay vacant.
     */
    suspend fun reserveVacated(
        batch: ImportBatch,
        vacating: List<TransactionId>,
    ): ReservedTransactions {
        val observations = observationRepository
        if (batch.csvImportMutations.none { it is CsvImportMutation.Reserve } &&
            batch.apiSessionMutations.none { it is ApiSessionMutation.Reserve }
        ) {
            return ReservedTransactions()
        }
        val vacatingIds = vacating.toSet()
        // Only this batch's own files and sessions: a stopped re-run of another source leaves its reservations vacant, and settling them
        // here would delete a pinned, split or copied transaction that run's records are still to take back.
        val ownTransactions = transactionsObservedByResets(batch)
        // An observation a person pinned or split stays reserved for its record to take back: deleting it would drop the
        // decision with it. So does a copy (#1475): the settle books it again from what its record says now.
        val decided =
            personOverrideRepository.getAllPins().mapTo(mutableSetOf()) { it.observationId } +
                personOverrideRepository.getAllSplits().flatMap { listOf(it.first, it.second) }
        val reserved = observations.getReserved()
        val reservedIds = reserved.mapTo(mutableSetOf()) { it.id }
        // A split gives an observation a transaction of its own, which no source created and so no re-run deletes: when
        // only reserved observations say it, it is vacated with the rest, so the record takes the id back.
        val decidedAlone =
            reserved
                .asSequence()
                .filter { it.id in decided || it.isCopy() }
                .filter { it.transactionId !in vacatingIds && it.transactionId in ownTransactions }
                .map { it.transactionId }
                .toSet()
                .filterTo(mutableSetOf()) { id -> observations.getObservationsOfTransaction(id).all { it.id in reservedIds } }
        val kept =
            reserved
                .filter { it.transactionId in vacatingIds || it.transactionId in decidedAlone }
                .mapTo(mutableSetOf()) { it.transactionId }
        val overridden = personOverrideRepository.getOverriddenTransactions().intersect(kept)
        val placeholderTypeId = if (overridden.isEmpty()) null else placeholderTypeId()
        val priorMerges =
            observations
                .getObservationsOfTransactions(overridden)
                .groupBy({ it.transactionId }, { it.content })
                .mapNotNull { (id, contents) ->
                    Materializer.merge(contents, placeholderTypeId)?.let { id to it }
                }.toMap()
        // What its import related them to waits with them, for the re-run to say again (#1510).
        relationshipRepository.reserveImportMadeOf(kept)
        return ReservedTransactions(kept, priorMerges)
    }

    /**
     * Settles a re-run's [reserved] ids once it has run (#1430): an id nothing took back is released, a reserved
     * observation its record didn't say again goes (a group's is kept: its other legs may come from files not re-run), and
     * every id a person overrode, or that lost an observation, is rebuilt with the overrides on top. A conflict compares
     * the new merge with the one before the re-run.
     */
    suspend fun settle(
        reserved: ReservedTransactions,
        onProgress: (suspend (Progress) -> Unit)?,
    ): MaterializationReport {
        val observations = observationRepository
        onProgress?.invoke(stepProgress("Booking again the reconciliation copies the files still report", 0, SETTLE_STEPS))
        val recopied = copier.recopy(reserved)
        val remaining = reserved.ids - recopied.ids
        // What no record took back still stands as it was before the re-run (#1510): it goes now, and what it was
        // reconciled with is rebuilt, as a fresh import of the others has it.
        val vacant = observations.getVacantTransactionIds(remaining)
        onProgress?.invoke(stepProgress("Deleting ${vacant.size} transactions no record took back", 1, SETTLE_STEPS))
        val partners = reconciledPartnersOf(vacant.toList(), emptySet())
        relationshipRepository.deleteReserved(reserved.ids)
        vacant.filterIsInstance<TransferId>().chunked(DELETE_CHUNK_ROWS).forEach { transactionRepository.deleteTransactions(it) }
        vacant.filterIsInstance<TradeId>().chunked(DELETE_CHUNK_ROWS).forEach { tradeRepository.deleteTrades(it) }
        observations.releaseTransactions(vacant)
        val reconciled =
            observations.getObservationsOfTransactions(partners - vacant).mapTo(mutableSetOf()) { it.transactionId }
        onProgress?.invoke(stepProgress("Rebuilding ${reconciled.size} transactions they were reconciled with", 2, SETTLE_STEPS))
        if (reconciled.isNotEmpty()) materialize(emptyList(), MaterializationRequest(Source.Reconciler, reconciled))
        val pending = observations.getReserved()
        onProgress?.invoke(
            stepProgress("Dropping observations no record said again: 0 of ${pending.size} checked", DROP_STEP, SETTLE_STEPS),
        )
        val shared =
            observations.getMaterializedIds(
                pending.mapNotNullTo(mutableSetOf()) { observation ->
                    observation.transactionId.takeIf { observation.owner !is ObservationOwner.Group && it !in reserved.ids }
                },
            )
        val lost = mutableSetOf<TransactionId>()
        var checked = 0
        for (chunk in pending.chunked(OBSERVATION_CHUNK_ROWS)) {
            val dropped = mutableListOf<ObservationChange>()
            val keep = mutableListOf<ObservationId>()
            for (observation in chunk) {
                when {
                    observation.owner is ObservationOwner.Group -> {
                        keep += observation.id
                    }

                    observation.transactionId in reserved.ids || observation.transactionId in shared -> {
                        // Includes pins and splits that remained on a transaction another source holds.
                        dropped += ObservationChange(observation.owner, observation.slot, observation, null)
                        lost += observation.transactionId
                    }
                }
            }
            observations.applyObservationChanges(emptyList(), dropped, emptyList(), reclaimed = keep)
            checked += chunk.size
            onProgress?.invoke(
                Progress(
                    "Dropping observations no record said again: $checked of ${pending.size} checked",
                    fraction = (DROP_STEP + checked.toFloat() / pending.size) / SETTLE_STEPS,
                ),
            )
        }
        val rebuild =
            (reserved.priorMerges.keys - vacant - recopied.ids) +
                observations.getObservationsOfTransactions(lost).mapTo(mutableSetOf()) { it.transactionId }
        val released = reserved.priorMerges.keys.filterTo(mutableSetOf()) { it in vacant }
        onProgress?.invoke(stepProgress("Rebuilding ${rebuild.size} transactions with hand edits or lost observations", 4, SETTLE_STEPS))
        if (rebuild.isEmpty()) {
            onProgress?.invoke(stepProgress("Settled", SETTLE_STEPS, SETTLE_STEPS))
            return recopied.report.copy(releasedOverridden = released)
        }
        val report = materialize(emptyList(), MaterializationRequest(Source.Manual, rebuild, priorMerges = reserved.priorMerges))
        onProgress?.invoke(stepProgress("Settled", SETTLE_STEPS, SETTLE_STEPS))
        return report.copy(
            inserted = report.inserted + recopied.report.inserted,
            updated = report.updated + recopied.report.updated,
            conflicts = recopied.report.conflicts + report.conflicts,
            releasedOverridden = released,
        )
    }

    /** The transactions the records of the files and API sessions [batch] resets observe, read before the reset drops them. */
    suspend fun transactionsObservedByResets(batch: ImportBatch): Set<TransactionId> {
        val observations = observationRepository
        val resetIds =
            batch.csvImportMutations.mapNotNullTo(mutableSetOf()) {
                when (it) {
                    is CsvImportMutation.ResetToUnimported -> it.id
                    is CsvImportMutation.Reserve -> it.id
                    else -> null
                }
            }
        val resetSessions =
            batch.apiSessionMutations.mapNotNullTo(mutableSetOf()) {
                when (it) {
                    is ApiSessionMutation.ResetToUnimported -> it.sessionId
                    is ApiSessionMutation.Reserve -> it.sessionId
                    else -> null
                }
            }
        return observations.getRecordTransactions(resetIds, resetSessions)
    }

    /**
     * The transactions in a `reconciled` link with any of [deleting] that outlive the delete (neither among them nor
     * [kept]), read before the rows and their memberships go.
     */
    suspend fun reconciledPartnersOf(
        deleting: List<TransactionId>,
        kept: Set<TransactionId>,
    ): Set<TransactionId> {
        val gone = deleting.filterNot { it in kept }
        if (gone.isEmpty()) return emptySet()
        val goneIds = gone.mapTo(mutableSetOf()) { it.id }
        return relationshipRepository
            .getByTransactions(gone)
            .filter { it.relationshipType.id.id == WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID }
            .flatMap { link -> link.members.map { it.transactionId } }
            .filterTo(mutableSetOf()) { it.id !in goneIds }
    }

    /**
     * Which of [ids] another source's record still observes, once the batch's resets dropped its own sources'. The
     * deleting sources' own records don't count: a file being unimported still has its records until a later reset.
     */
    suspend fun stillObserved(
        ids: List<TransactionId>,
        deletingSources: Set<Source>,
    ): Set<TransactionId> {
        val observations = observationRepository
        val csvImports = deletingSources.filterIsInstance<Source.File>().mapTo(mutableSetOf()) { it.importId }
        val apiSessions = deletingSources.filterIsInstance<Source.Api>().mapTo(mutableSetOf()) { it.sessionId }
        return observations.getTransactionsObservedByOtherRecords(ids, csvImports, apiSessions)
    }

    /**
     * Deletes the batch's DELETE transfers and trades, [onProgress] reporting each chunk. A transaction another record still
     * observes outlives its creator, and a re-run's are reserved rather than deleted (#1510); what [sharedByResets] (the
     * transactions a reset file shared with other records) and the withdrawn records' reconciled partners still observe is
     * rebuilt from what is left. Returns the re-run's reservations.
     */
    suspend fun deleteTransactions(
        batch: ImportBatch,
        sharedByResets: Set<TransactionId>,
        onProgress: (suspend (Progress) -> Unit)?,
    ): ReservedTransactions {
        // Transfers and trades are the only deletes a batch holds in bulk (a re-import removes every row
        // its session created before re-running it), so they report progress like the write phase does —
        // otherwise a caller's bar holds still through the slower half of a re-import.
        val transferDeletes = batch.transfers.deletes()
        val tradeDeletes = batch.trades.deletes()
        val deleteTotal = transferDeletes.size + tradeDeletes.size
        var deleted = 0

        suspend fun reportDeleted(count: Int) {
            deleted += count
            onProgress?.invoke(
                Progress(
                    detail = "Deleting transactions",
                    fraction = deleted.toFloat() / deleteTotal,
                    processed = deleted,
                    total = deleteTotal,
                ),
            )
        }

        // A transaction another record still observes (a joined twin) outlives its creator: it is rebuilt from what is
        // left, as is one that only lost a reset file's observations.
        val transferDeleteIds = transferDeletes.map { requireNotNull(it.existingId) }
        val tradeDeleteIds = tradeDeletes.map { requireNotNull(it.existingId) }
        val stillObserved =
            stillObserved(
                // A person deleting a transaction deletes it; only a source withdrawing its records leaves the others standing.
                transferDeletes.filter { it.source.isImport() }.map { requireNotNull(it.existingId) } +
                    tradeDeletes.filter { it.source.isImport() }.map { requireNotNull(it.existingId) },
                (transferDeletes.map { it.source } + tradeDeletes.map { it.source }).toSet(),
            )
        // A re-run's reserved transactions keep their rows (#1510): each stands vacant, hidden from the re-run's own dedupe
        // and matching, until its record says it again (and it is written only if what it says changed) or the settle
        // deletes it. Their links stay with them.
        val reserved = reserveVacated(batch, (transferDeleteIds + tradeDeleteIds).filterNot { it in stillObserved })
        // What a withdrawn record was reconciled with is rebuilt once it goes: a link left with one member is pruned, so
        // the exclusion it held lifts, as a fresh import of the others has it.
        val partners =
            reconciledPartnersOf(
                transferDeletes.filter { it.source.isImport() }.map { requireNotNull(it.existingId) } +
                    tradeDeletes.filter { it.source.isImport() }.map { requireNotNull(it.existingId) },
                stillObserved + reserved.ids,
            )
        transferDeleteIds.filterNot { it in stillObserved }.chunked(DELETE_CHUNK_ROWS).forEach { chunk ->
            val gone = chunk.filterNot { it in reserved.ids }
            observationRepository.deleteObservationsOfTransactions(gone)
            transactionRepository.deleteTransactions(gone)
            reportDeleted(chunk.size)
        }
        tradeDeleteIds.filterNot { it in stillObserved }.chunked(DELETE_CHUNK_ROWS).forEach { chunk ->
            val gone = chunk.filterNot { it in reserved.ids }
            observationRepository.deleteObservationsOfTransactions(gone)
            tradeRepository.deleteTrades(gone)
            reportDeleted(chunk.size)
        }
        // A transaction the reset left with no observation at all stays as its import booked it until the file's re-import:
        // rebuilding it from nothing would delete what the reset file's other rows still report.
        val rebuildCandidates = sharedByResets - (transferDeleteIds + tradeDeleteIds).toSet() + partners - reserved.ids
        val rebuilt =
            observationRepository
                .getObservationsOfTransactions(rebuildCandidates)
                .mapTo(stillObserved.toMutableSet()) { it.transactionId }
        if (rebuilt.isNotEmpty()) materialize(emptyList(), MaterializationRequest(Source.Reconciler, rebuilt))
        return reserved
    }
}

private fun Source.isImport(): Boolean = this is Source.File || this is Source.Api
