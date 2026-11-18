package com.moneymanager.importer

import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.observation.MaterializedWrite
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationEnd
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.ImportRecordReadRepository
import com.moneymanager.domain.repository.ObservationReadRepository
import com.moneymanager.domain.repository.RelationshipReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.importengineapi.CsvTransferSync
import com.moneymanager.importengineapi.ImportPlan
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

/** Plans in-place updates when every row owns exactly one independent transfer. */
internal class CsvTransferSyncPlanner(
    private val observations: ObservationReadRepository,
    private val records: ImportRecordReadRepository,
    private val csv: CsvImportReadRepository,
    private val transfers: TransactionReadRepository,
    private val relationships: RelationshipReadRepository,
    private val materializer: ObservationMaterializationPlanner,
) {
    suspend fun plan(request: CsvTransferSync): CsvTransferChanges? {
        val id = request.id
        val rows = request.rows
        val hashes = request.records
        if (rows.isEmpty() || rows.keys != hashes.contentHashes.keys) return null
        val accounts = rows.values.flatMapTo(mutableSetOf()) { listOf(it.transfer.sourceAccountId, it.transfer.targetAccountId) }
        val stored = observations.getObservationsOnAccounts(accounts)
        val bookings = csv.getImportRows(id, limit = rows.size, offset = 0).associate { it.rowIndex to it.transferId }
        val ids = bookings.values.filterNotNull().toSet()
        if (ids.size != rows.size || stored.size != ids.size || stored.any { it.transactionId !in ids }) return null
        if (csv.getTransferIdsCreatedByImport(id) != ids || csv.getTradeIdsCreatedByImport(id).isNotEmpty()) return null
        if (csv.getAccountsCreatedByImport(id).any { it !in accounts }) return null
        if (accounts.any { account -> transfers.getTransactionsByAccount(account).first().any { it.id !in ids } }) return null
        if (relationships.getByTransactions(ids).isNotEmpty()) return null

        val byTransaction = stored.associateBy { it.transactionId }
        val byRow = records.getCsvImportRecords(id).associateBy { (it.key as ImportRecordKey.CsvRow).rowIndex }
        if (byRow.keys != rows.keys) return null
        val mutations =
            rows.map { (index, input) ->
                val row = input.transfer
                val transactionId = bookings[index] ?: return null
                val booking = byTransaction[transactionId] ?: return null
                val record = byRow.getValue(index)
                if (booking.owner != ObservationOwner.Record(record.id) || booking.slot != ObservationSlot.MAIN) return null
                val previous = booking.content.values as? ObservedValues.Transfer ?: return null
                val ownEnd =
                    when (input.statementAccount) {
                        row.sourceAccountId -> ObservationEnd.SOURCE
                        row.targetAccountId -> ObservationEnd.TARGET
                        else -> null
                    }
                if (ownEnd != booking.content.facts.ownEnd) return null
                val timestamp = Instant.fromEpochMilliseconds(row.timestamp.toEpochMilliseconds())
                if (previous.sourceAccountId != row.sourceAccountId || previous.targetAccountId != row.targetAccountId ||
                    previous.assetId.id != row.amount.asset.id.id || previous.timestamp != timestamp ||
                    booking.content.attributes.isNotEmpty() ||
                    booking.content.facts.placeholderEnds
                        .isNotEmpty()
                ) {
                    return null
                }
                ObservationMutation.Upsert(
                    booking.owner,
                    booking.slot,
                    ObservationTransaction.Existing(booking.transactionId),
                    booking.content.copy(values = previous.copy(description = row.description, amount = row.amount.amount)),
                )
            }
        // The existing importer may deduplicate equal movements. Only use this path for distinct tuples.
        if (mutations.map { it.content.withoutDescription() }.distinct().size != rows.size) return null
        val changes = materializer.plan(mutations, MaterializationRequest(Source.Csv(id)))
        if (changes.rows.any { it !is MaterializedWrite.Update }) return null
        val current = transfers.getTransactionsByIds(ids)
        val updates =
            changes.rows.filterIsInstance<MaterializedWrite.Update>().map { write ->
                val before = current.getValue(TransferId(write.transactionId))
                val after = write.after.values as ObservedValues.Transfer
                if (before.amount.asset.id.id != after.assetId.id) return null
                PlannedUpdate(
                    before = before.planned(),
                    after =
                        PlannedTransaction(
                            before.id,
                            after.timestamp,
                            after.description,
                            listOf(PlannedLeg(after.sourceAccountId, after.targetAccountId, before.amount.copy(amount = after.amount))),
                        ),
                )
            }
        return CsvTransferChanges(
            changes,
            ImportPlan(updated = updates.filter { it.fields.isNotEmpty() }, conflicts = changes.report.conflicts),
            byRow.values
                .mapNotNull { record ->
                    val hash = hashes.contentHashes.getValue((record.key as ImportRecordKey.CsvRow).rowIndex)
                    if (record.contentHash == hash) null else record.id to hash
                }.toMap(),
        )
    }

    private fun ObservationContent.withoutDescription(): ObservedValues.Transfer =
        (values as ObservedValues.Transfer).copy(description = "")
}

internal data class CsvTransferChanges(
    val materialization: ObservationMaterializationPlan,
    val preview: ImportPlan,
    val hashes: Map<ImportRecordId, String>,
)
