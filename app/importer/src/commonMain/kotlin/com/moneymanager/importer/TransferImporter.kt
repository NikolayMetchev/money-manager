package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Relationship
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.withStatementAccount
import com.moneymanager.domain.repository.write.ObservationWriteRepository
import com.moneymanager.domain.repository.write.TransactionWriteRepository
import com.moneymanager.domain.repository.write.TransferEdit
import com.moneymanager.importengineapi.ExpandedTransfer
import com.moneymanager.importengineapi.ExpansionRow
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.ObservationExpander
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.importengineapi.RowOutcome
import com.moneymanager.importengineapi.observationKey
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * The transfers a batch books: its CREATE transfers deduplicated against what is booked and written with their legs, the
 * observations its duplicate rows add to what they duplicate, and the UPDATE transfers a person makes.
 */
internal class TransferImporter(
    private val transactionRepository: TransactionWriteRepository,
    private val observationRepository: ObservationWriteRepository,
    private val existingTransfers: ExistingTransferLoader,
    private val reversalLinker: ReversalLinker,
    private val transferWriter: TransferWriter,
    private val tradeImporter: TradeImporter,
    private val handEdits: HandEdits,
    private val sourceObservations: () -> SourceObservations,
) {
    /** The transfer-related portion of an [ImportResult], produced by [importTransferCreates]. */
    class TransferOutcome(
        val imported: Int,
        val duplicates: Int,
        val updated: Int,
        val excluded: Int,
        val createdTransferIds: Map<ImportRowKey, TransferId>,
        val rowOutcomes: Map<ImportRowKey, RowOutcome>,
        val orderedRowOutcomes: List<RowOutcome>,
        /** The existing transactions this batch's duplicate rows now also observe, which their merge must take in. */
        val observedDuplicates: Set<TransactionId> = emptySet(),
        /** The kept transactions a re-run took back (#1510). */
        val retaken: Set<TransactionId> = emptySet(),
    )

    /**
     * Runs the existing dedupe + write path over the CREATE transfers, resolving account references and
     * synthesising a [ImportRowKey.Manual] for manually-entered transfers that carry no source row.
     */
    suspend fun importTransferCreates(
        creates: List<ImportTransfer>,
        batch: ImportBatch,
        accountKeyToId: Map<LocalAccountKey, AccountId>,
        batchSize: Int,
        onProgress: (suspend (Progress) -> Unit)?,
    ): TransferOutcome {
        if (creates.isEmpty()) return TransferOutcome(0, 0, 0, 0, emptyMap(), emptyMap(), emptyList())

        var manualRowIndex = 0L
        val resolvedTransfers =
            creates.map { transfer ->
                val fee = transfer.fee
                val passThrough = transfer.passThrough
                transfer.copy(
                    rowKey = transfer.rowKey ?: ImportRowKey.Manual(manualRowIndex++),
                    source =
                        transfer.statementAccount
                            ?.let { transfer.source.withStatementAccount(requireId(resolveRef(it, accountKeyToId))) }
                            ?: transfer.source,
                    fromAccount = resolveRef(requireNotNull(transfer.fromAccount), accountKeyToId),
                    toAccount = resolveRef(requireNotNull(transfer.toAccount), accountKeyToId),
                    fee =
                        fee?.copy(
                            source = resolveRef(fee.source, accountKeyToId),
                            target = resolveRef(fee.target, accountKeyToId),
                        ),
                    passThrough =
                        passThrough?.copy(
                            conduits = passThrough.conduits.map { resolveRef(it, accountKeyToId) },
                            merchantTarget = resolveRef(passThrough.merchantTarget, accountKeyToId),
                        ),
                )
            }

        onProgress?.invoke(Progress("Detecting duplicates"))
        val calendars = existingTransfers.settlementCalendars(batch.dedupePolicy)
        // A re-run's vacated rows stand for what it is re-booking, so it books them afresh rather than matching them (#1510).
        val vacant = observationRepository.getVacantTransactionIds()
        val existing = existingTransfers.loadExisting(resolvedTransfers, batch, calendars).filterNot { it.transferId in vacant }
        val existingRelationships =
            existingTransfers.existingRelationships(existing).filterNot { relationship ->
                relationship.members.any {
                    it.transactionId in
                        vacant
                }
            }
        val onward = existingTransfers.passThroughOnward(existing, existingRelationships)
        val classified =
            ImportDeduper(
                batch.dedupePolicy,
                existing,
                existingTransfers.ownSourceLegs(resolvedTransfers, existing),
            ).classify(resolvedTransfers)

        val toImport = reversalLinker.resolveReversalLinks(classified.filter { it.status == ImportStatus.IMPORTED })
        val toUpdate = classified.filter { it.status == ImportStatus.UPDATED }
        val duplicates = classified.count { it.status == ImportStatus.DUPLICATE }
        val excluded = resolvedTransfers.count { it.excludedFromBalances }

        onProgress?.invoke(Progress("Importing transactions", fraction = 0f, processed = 0, total = toImport.size))
        // The engine's own cross-source reconcile marker is never "owned" by a source: a row that
        // re-imports must not un-exclude a transfer the engine reconciled away.
        val engineOwnedTypeId = batch.dedupePolicy.reconciledExclusionAttributeTypeId
        val createdLegs = mutableListOf<Pair<ExpandedTransfer, TransferId>>()
        val observer = sourceObservations()
        // A re-run's row that still says its reserved transaction is recorded as its observation and brought to the merge of every
        // record on it below (#1510): writing what this row alone says first would rewrite a twin only to write it back.
        val directUpdates = toUpdate.filterNot { observer.isReserved(it.transfer.observationKey()) }
        val createdIds =
            transferWriter.writeTransfers(
                toImport,
                directUpdates,
                batchSize,
                onProgress,
                engineOwnedTypeId,
                createdLegs,
                observer,
            )
        if (createdLegs.isNotEmpty()) onProgress?.invoke(Progress("Recording observations"))
        val approximate =
            toImport.mapNotNullTo(mutableSetOf()) { c -> c.transfer.takeIf { it.approximateUntil != null }?.observationKey() }
        observer.recordTransfers(createdLegs.map { (leg, id) -> TransferObservation(leg, id, leg.key in approximate) })
        tradeImporter.writeConversionTrades(toImport, observer)

        val createdTransferIds = toImport.zip(createdIds).associate { (c, id) -> requireNotNull(c.transfer.rowKey) to id }

        // Build per-transfer outcomes in input order; IMPORTED rows pull their created id in turn.
        val createdIdByIndex = mutableMapOf<Int, TransferId>()
        var importPointer = 0
        classified.forEachIndexed { i, c ->
            if (c.status == ImportStatus.IMPORTED) {
                createdIds.getOrNull(importPointer++)?.let { createdIdByIndex[i] = it }
            }
        }
        val orderedRowOutcomes =
            classified.mapIndexed { i, c ->
                when (c.status) {
                    ImportStatus.IMPORTED -> RowOutcome(ImportStatus.IMPORTED, createdIdByIndex[i])

                    // For an in-batch duplicate, resolve to the earlier accepted transfer's created id.
                    else -> RowOutcome(c.status, c.existing ?: c.inBatchMatchIndex?.let { createdIdByIndex[it] })
                }
            }
        val rowOutcomes =
            classified.zip(orderedRowOutcomes).associate { (c, outcome) -> requireNotNull(c.transfer.rowKey) to outcome }
        val observedDuplicates = observeDuplicates(classified, orderedRowOutcomes, existingRelationships, onward, observer, onProgress)

        return TransferOutcome(
            imported = toImport.size,
            duplicates = duplicates,
            updated = toUpdate.size,
            excluded = excluded,
            createdTransferIds = createdTransferIds,
            rowOutcomes = rowOutcomes,
            orderedRowOutcomes = orderedRowOutcomes,
            observedDuplicates = observedDuplicates,
            retaken = observer.claimed,
        )
    }

    /**
     * A row that duplicates a transfer already booked (or one this batch books) says that transfer too: its main leg,
     * and its fee leg when the transfer has a fee, observe it beside what booked it, so twins are one transaction with
     * several observations. A row seen before (a re-import of its file) already says its slots and keeps them.
     * A row that updates a transfer says it only when its re-run reserved the slot (#1429): it takes its observation back
     * there, so what a person decided about it stays; a fresh import leaves the update to the transfer alone.
     */
    private suspend fun observeDuplicates(
        classified: List<Classified>,
        outcomes: List<RowOutcome>,
        existingRelationships: List<Relationship>,
        onward: Map<TransferId, List<Transfer>>,
        observer: SourceObservations,
        onProgress: (suspend (Progress) -> Unit)?,
    ): Set<TransactionId> {
        val duplicates =
            classified.zip(outcomes).mapNotNull { (c, outcome) ->
                outcome.transferId
                    ?.takeIf {
                        c.status == ImportStatus.DUPLICATE ||
                            (c.status == ImportStatus.UPDATED && observer.isReserved(c.transfer.observationKey()))
                    }?.let { c to it }
            }
        if (duplicates.isEmpty()) return emptySet()
        onProgress?.invoke(Progress("Recording duplicate rows"))
        val feeLegs =
            existingRelationships
                .mapNotNull { relationship ->
                    relationship.transferPair?.let { (primary, related) -> (primary to relationship.relationshipType.id) to related }
                }.toMap()
        val rows = ObservationExpander.expandTransfers(duplicates.map { (c, _) -> ExpansionRow(c.transfer) }, emptyMap())
        val legs =
            buildList {
                for ((duplicate, row) in duplicates.zip(rows)) {
                    val (c, id) = duplicate
                    val approximate = c.transfer.approximateUntil != null
                    add(TransferObservation(row.main, id, approximate))
                    val feeLeg = row.legs.firstOrNull { it.key.slot == ObservationSlot.FEE }
                    val feeId = c.transfer.fee?.let { feeLegs[id to it.relationshipTypeId] }
                    if (feeLeg != null && feeId != null) add(TransferObservation(feeLeg, feeId, approximate))
                    // The onward movement outlives whichever of the two records is unimported first.
                    row.legs
                        .filter { it.key.slot.startsWith(ObservationSlot.CONDUIT_PREFIX) }
                        .zip(onward[id].orEmpty())
                        .takeWhile { (leg, booked) ->
                            leg.sourceAccountId == booked.sourceAccountId && leg.targetAccountId == booked.targetAccountId
                        }.forEach { (leg, booked) ->
                            add(TransferObservation(leg, booked.id, approximate))
                        }
                }
            }
        return observer.recordTransfers(legs)
    }

    /** Applies the batch's UPDATE transfers. A person's edits become their overrides as well, so a rebuild from the observations keeps them. */
    suspend fun applyUpdates(batch: ImportBatch) {
        val handEditSnapshots = handEdits.snapshot(batch.transfers.updates())
        transactionRepository.updateTransfers(
            batch.transfers.updates().map { intent ->
                TransferEdit(
                    transfer = intent.toUpdatedTransfer(),
                    transactionId = requireNotNull(intent.existingId),
                    source = intent.source,
                    deletedAttributeIds = intent.deletedAttributeIds,
                    updatedAttributes = intent.updatedAttributes,
                    newAttributes = intent.attributes,
                )
            },
        )
        handEdits.record(handEditSnapshots)
    }

    /** Builds the [Transfer] for an UPDATE intent, or null for an attribute-only update. */
    private fun ImportTransfer.toUpdatedTransfer(): Transfer? =
        if (fromAccount == null && toAccount == null && amount == null && timestamp == null) {
            null
        } else {
            Transfer(
                id = requireNotNull(existingId),
                timestamp = requireNotNull(timestamp),
                description = description,
                sourceAccountId = requireId(fromAccount),
                targetAccountId = requireId(toAccount),
                amount = requireNotNull(amount),
            )
        }
}
