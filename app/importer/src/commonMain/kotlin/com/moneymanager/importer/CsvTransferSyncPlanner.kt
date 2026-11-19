package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.observation.ImportRecord
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.observation.MaterializedWrite
import com.moneymanager.domain.model.observation.Observation
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationEnd
import com.moneymanager.domain.model.observation.ObservationFacts
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservedAssetId
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.ImportRecordReadRepository
import com.moneymanager.domain.repository.ObservationReadRepository
import com.moneymanager.domain.repository.PersonOverrideReadRepository
import com.moneymanager.domain.repository.RelationshipReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.CsvTransferSync
import com.moneymanager.importengineapi.CsvTransferSyncRow
import com.moneymanager.importengineapi.ImportPlan
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalTransactionKey
import com.moneymanager.importengineapi.MaterializationRequest
import com.moneymanager.importengineapi.ObservationMutation
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.importengineapi.ObservationTransaction
import com.moneymanager.importengineapi.PlannedLeg
import com.moneymanager.importengineapi.PlannedTransaction
import com.moneymanager.importengineapi.PlannedUpdate
import com.moneymanager.importengineapi.planned
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

/** Plans changes to the independent transfers a CSV's records report. */
internal class CsvTransferSyncPlanner(
    private val observations: ObservationReadRepository,
    private val records: ImportRecordReadRepository,
    private val csv: CsvImportReadRepository,
    private val transfers: TransactionReadRepository,
    private val relationships: RelationshipReadRepository,
    private val overrides: PersonOverrideReadRepository,
    private val materializer: ObservationMaterializationPlanner,
    private val sourceSnapshots: suspend (List<Transfer>, List<ImportTransfer>, String?) -> List<Transfer>,
) {
    suspend fun plan(
        request: CsvTransferSync,
        allocatedLocals: Int,
        allocatedRecords: Int,
        replacedBookings: Set<TransferId>,
        earlierBookings: List<Transfer>,
    ): CsvTransferChanges? {
        val id = request.id
        val rows = request.rows
        val hashes = request.records.contentHashes
        if (hashes.isEmpty() || rows.keys.any { it !in hashes }) return null
        val staged = csv.getImportRows(id, limit = hashes.size, offset = 0).associateBy { it.rowIndex }
        val recordsByRow = records.getCsvImportRecords(id).associateBy { (it.key as ImportRecordKey.CsvRow).rowIndex }
        if (staged.keys != hashes.keys || recordsByRow.keys.any { it !in staged }) return null
        val bookings = staged.filterValues { it.importStatus == ImportStatus.IMPORTED }.mapValues { it.value.transferId ?: return null }
        if (staged.any { (index, row) ->
                when (row.importStatus) {
                    ImportStatus.IMPORTED, ImportStatus.SKIPPED -> index !in recordsByRow
                    null -> row.transferId != null
                    else -> true
                }
            }
        ) {
            return null
        }
        val newRecords = mutableListOf<ImportRecord>()
        val recordIds =
            staged.keys.associateWith { index ->
                recordsByRow[index]?.id ?: ImportRecordId(-(allocatedRecords.toLong() + newRecords.size + 1)).also { provisional ->
                    newRecords += ImportRecord(provisional, ImportRecordKey.CsvRow(id, index), hashes.getValue(index))
                }
            }

        val ids = bookings.values.toSet()
        if (ids.size != bookings.size || csv.getTransferIdsCreatedByImport(id) != ids ||
            csv.getTradeIdsCreatedByImport(id).isNotEmpty()
        ) {
            return null
        }
        if (relationships.getByTransactions(ids).isNotEmpty()) return null

        val proposed = rows.mapValues { (_, input) -> input.observed() }
        val proposedAccounts = proposed.values.flatMapTo(mutableSetOf()) { listOf(it.sourceAccountId, it.targetAccountId) }
        if (csv.getAccountsCreatedByImport(id).any { it !in proposedAccounts }) return null
        val current = transfers.getTransactionsByIds(ids)
        if (current.keys != ids) return null
        val stored = observations.getObservationsOfRecords(recordsByRow.values.map { it.id })
        if (stored.size != ids.size || stored.any { it.transactionId !in ids }) return null
        val byTransaction = stored.associateBy { it.transactionId }
        val accounts = proposedAccounts + current.values.flatMap { listOf(it.sourceAccountId, it.targetAccountId) }
        val related = observations.getObservationsOfTransactions(ids)
        if (observations.getReservedIds(related.map { it.id }).isNotEmpty()) return null
        val outside =
            accounts
                .flatMap { account -> transfers.getTransactionsByAccount(account).first() }
                .distinctBy { it.id }
                .filter { it.id !in replacedBookings }
        // Before the final observation match, the normal importer can deduplicate against an earlier file.
        // Only use this plan when replay would give each row its own preliminary booking.
        if (outside.isNotEmpty() || earlierBookings.isNotEmpty()) {
            val intents =
                rows.values.map { input ->
                    val row = input.transfer
                    ImportTransfer(
                        source = Source.Csv(id),
                        fromAccount = AccountRef.Existing(row.sourceAccountId),
                        toAccount = AccountRef.Existing(row.targetAccountId),
                        timestamp = row.timestamp,
                        description = row.description,
                        amount = row.amount,
                    )
                }
            val competing =
                (sourceSnapshots(outside, intents, request.application.strategyId.toString()) + earlierBookings).map { transfer ->
                    ExistingTransferInfo(transfer.id, transfer, transfer.attributes.associate { it.attributeType.id to it.value })
                }
            if (ImportDeduper(request.dedupePolicy, competing).classify(intents).any { it.status != ImportStatus.IMPORTED }) return null
        }
        if (proposed.values
                .map { it.copy(description = "") }
                .distinct()
                .size != rows.size
        ) {
            return null
        }

        val newSources = mutableMapOf<LocalTransactionKey, Source.Csv>()
        val mutations =
            recordIds.mapNotNull { (index, recordId) ->
                val owner = ObservationOwner.Record(recordId)
                val existingId = bookings[index]
                val values = proposed[index]
                val input = rows[index]
                val booking = existingId?.let { byTransaction[it] ?: return null }
                if (booking != null && !booking.isIndependentMain(owner)) return null
                if (values == null || input == null) {
                    return@mapNotNull booking?.let { ObservationMutation.Delete(owner, ObservationSlot.MAIN) }
                }
                val ownEnd =
                    when (input.statementAccount) {
                        values.sourceAccountId -> ObservationEnd.SOURCE
                        values.targetAccountId -> ObservationEnd.TARGET
                        else -> null
                    }
                if (booking != null) {
                    ObservationMutation.Upsert(
                        owner,
                        ObservationSlot.MAIN,
                        ObservationTransaction.Existing(booking.transactionId),
                        booking.content.copy(values = values, facts = booking.content.facts.copy(ownEnd = ownEnd)),
                    )
                } else {
                    val key = LocalTransactionKey(index.toString())
                    newSources[key] = Source.Csv(id, index, input.statementAccount)
                    ObservationMutation.Upsert(
                        owner,
                        ObservationSlot.MAIN,
                        ObservationTransaction.New(key),
                        ObservationContent(values, facts = ObservationFacts(ownEnd = ownEnd)),
                    )
                }
            }
        val changes = materializer.plan(mutations, MaterializationRequest(Source.Csv(id)), allocatedLocals)
        val inputByNewId = changes.newTransactions.entries.associate { (key, localId) -> localId to newSources.getValue(key) }
        val created = mutableListOf<PlannedTransaction>()
        val updated = mutableListOf<PlannedUpdate>()
        val removed = mutableListOf<PlannedTransaction>()
        val indexByBooking = bookings.entries.associate { (index, booking) -> booking to index }
        val writes =
            changes.rows.map { write ->
                val transferId = TransferId(write.transactionId)
                when (write) {
                    is MaterializedWrite.Insert -> {
                        val source = inputByNewId.getValue(transferId)
                        val input = rows.getValue(checkNotNull(source.rowIndex)).transfer
                        val values = write.row.values as ObservedValues.Transfer
                        created += input.copy(id = transferId, timestamp = values.timestamp).planned()
                        write.copy(source = source)
                    }

                    is MaterializedWrite.Update -> {
                        val before = current.getValue(transferId)
                        val after = write.after.values as ObservedValues.Transfer
                        val rowIndex = indexByBooking.getValue(transferId)
                        val input = rows.getValue(rowIndex)
                        val asset =
                            listOf(before.amount.asset, input.transfer.amount.asset).firstOrNull { it.id.id == after.assetId.id }
                                ?: return null
                        val update =
                            PlannedUpdate(
                                before = before.planned(),
                                after =
                                    PlannedTransaction(
                                        before.id,
                                        after.timestamp,
                                        after.description,
                                        listOf(
                                            PlannedLeg(
                                                after.sourceAccountId,
                                                after.targetAccountId,
                                                Money(after.amount, asset),
                                            ),
                                        ),
                                    ),
                            )
                        if (update.fields.isNotEmpty()) updated += update
                        write.copy(source = Source.Csv(id, rowIndex, input.statementAccount))
                    }

                    is MaterializedWrite.Delete -> {
                        removed += current.getValue(transferId).planned()
                        write
                    }
                }
            }
        val statuses =
            staged
                .mapNotNull { (index, row) ->
                    val status = if (index in rows) ImportStatus.IMPORTED else ImportStatus.SKIPPED
                    if (status == row.importStatus) null else index to status
                }.toMap()
        val deleted = changes.report.deleted
        return CsvTransferChanges(
            changes.copy(rows = writes),
            ImportPlan(
                created = created,
                updated = updated,
                removed = removed,
                conflicts = changes.report.conflicts,
                releasedOverrides = if (deleted.isEmpty()) emptySet() else overrides.getOverriddenTransactions().intersect(deleted),
            ),
            recordsByRow
                .mapNotNull { (index, record) ->
                    val hash = hashes.getValue(index)
                    if (record.contentHash == hash) null else record.id to hash
                }.toMap(),
            statuses,
            newRecords,
            ids,
        )
    }

    private fun Observation.isIndependentMain(expectedOwner: ObservationOwner): Boolean =
        owner == expectedOwner && slot == ObservationSlot.MAIN && content.attributes.isEmpty() && content.facts.placeholderEnds.isEmpty()

    private fun CsvTransferSyncRow.observed(): ObservedValues.Transfer =
        ObservedValues.Transfer(
            Instant.fromEpochMilliseconds(transfer.timestamp.toEpochMilliseconds()),
            transfer.description,
            transfer.sourceAccountId,
            transfer.targetAccountId,
            ObservedAssetId(transfer.amount.asset.id.id),
            transfer.amount.amount,
        )
}

internal data class CsvTransferChanges(
    val materialization: ObservationMaterializationPlan,
    val preview: ImportPlan,
    val hashes: Map<ImportRecordId, String>,
    val statuses: Map<Long, ImportStatus>,
    val newRecords: List<ImportRecord>,
    val transactionIds: Set<TransferId>,
) {
    val hasWrites: Boolean
        get() =
            materialization.observations.isNotEmpty() || materialization.rows.isNotEmpty() || hashes.isNotEmpty() ||
                statuses.isNotEmpty() || newRecords.isNotEmpty()
}
