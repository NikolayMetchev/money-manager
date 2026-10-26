package com.moneymanager.importengineapi

import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferAuditEntry
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.QifImportReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.tasks.PhasedProgress
import com.moneymanager.tasks.Progress
import kotlin.time.Instant

/** The reads a re-import needs, whatever the source. */
class RerunReads(
    val transactionRepository: TransactionReadRepository,
    val transferSourceRepository: TransferSourceReadRepository,
    val transferRelationshipRepository: TransferRelationshipReadRepository,
    val auditRepository: AuditReadRepository,
    val accountRepository: AccountReadRepository,
    val csvImportRepository: CsvImportReadRepository,
    val qifImportRepository: QifImportReadRepository,
    /** Null for a source that never creates trades (QIF). */
    val tradeRepository: TradeReadRepository?,
)

/**
 * Rows of other CSV files and records of other QIF files that were matched (DUPLICATE or UPDATED)
 * against a transaction a re-import deletes, found from their provenance. They are reset with it and re-run after it, so they match
 * the re-created transaction instead of pointing at a deleted one.
 */
data class AffectedRecords(
    /** Each file's records, keyed by the whole file: a [Source.Csv] or [Source.Qif] with no row. */
    val byFile: Map<Source, List<Long>> = emptyMap(),
) {
    val isEmpty: Boolean get() = byFile.isEmpty()
    val count: Int get() = byFile.values.sumOf { it.size }

    /** The mutations that return these records to never-imported. */
    fun resetMutations(): ImportBatch =
        ImportBatch(
            csvImportMutations =
                byFile.mapNotNull { (file, rows) -> (file as? Source.Csv)?.let { CsvImportMutation.ResetRowStatuses(it.importId, rows) } },
            qifImportMutations =
                byFile.mapNotNull { (file, records) ->
                    (file as? Source.Qif)?.let { QifImportMutation.ResetRecordStatuses(it.importId, records) }
                },
        )

    companion object {
        /** Groups [records] (each a CSV row or QIF record) by file, leaving out [excluding]'s own. */
        fun of(
            records: List<Source>,
            excluding: Source? = null,
        ): AffectedRecords =
            AffectedRecords(
                records
                    .mapNotNull { record ->
                        when (record) {
                            is Source.Csv -> record.rowIndex?.let { Source.Csv(record.importId) to it }
                            is Source.Qif -> record.recordIndex?.let { Source.Qif(record.importId) to it }
                            else -> null
                        }
                    }.filter { (file, _) -> file != excluding?.wholeFile() }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, indexes) -> indexes.distinct().sorted() },
            )

        private fun Source.wholeFile(): Source =
            when (this) {
                is Source.Csv -> Source.Csv(importId)
                is Source.Qif -> Source.Qif(importId)
                else -> this
            }
    }
}

/** Re-runs [AffectedRecords] with each file's own strategy; supplied by a caller that can run both kinds. */
fun interface AffectedRecordsRerunner {
    /**
     * Called before the re-import deletes anything, while the records still point at what they matched
     * (which is how a file's account is read); the returned function re-runs them after the re-import and
     * returns the records it couldn't.
     */
    suspend fun prepare(affected: AffectedRecords): suspend (onProgress: (suspend (Progress) -> Unit)?) -> AffectedRecords
}

/** The values of a transfer that a person can edit by hand. */
data class TransferValues(
    val timestamp: Instant,
    val description: String,
    val sourceAccountId: AccountId,
    val targetAccountId: AccountId,
    val amount: Money,
)

private fun Transfer.values() = TransferValues(timestamp, description, sourceAccountId, targetAccountId, amount)

private fun TransferAuditEntry.values() = TransferValues(timestamp, description, sourceAccountId, targetAccountId, amount)

/**
 * A hand edit of a transfer an import created. A re-import deletes the transfer and creates it again
 * from the same record, then re-applies the edit to the new one. Only what a person changed last is
 * kept: a field an import changed after the hand edit is the import's again.
 */
data class ManualEdit(
    val transferId: TransferId,
    /** The record the transfer came from, which identifies its replacement. */
    val record: Source,
    /** The transfer as the import left it, before the hand edits: what the replacement is matched on. */
    val imported: TransferValues,
    /** The edited values, only for the fields edited by hand. */
    val timestamp: Instant? = null,
    val description: String? = null,
    val sourceAccountId: AccountId? = null,
    val targetAccountId: AccountId? = null,
    val amount: Money? = null,
    /** Attribute types set or removed by hand, each with the values it holds now (empty when removed). */
    val attributes: Map<AttributeType, List<NewAttribute>> = emptyMap(),
) {
    val changesFields: Boolean
        get() = timestamp != null || description != null || sourceAccountId != null || targetAccountId != null || amount != null

    /** One line per hand-edited field or attribute type, for the re-import dialogs. */
    fun describeChanges(accountName: (AccountId) -> String): List<String> =
        buildList {
            timestamp?.let { add("Date → $it") }
            description?.let { add("Description → \"$it\"") }
            sourceAccountId?.let { add("From → ${accountName(it)}") }
            targetAccountId?.let { add("To → ${accountName(it)}") }
            amount?.let { add("Amount → ${it.toDisplayValue()} ${it.asset.code}") }
            attributes.forEach { (type, values) ->
                add(if (values.isEmpty()) "${type.name} removed" else "${type.name} → ${values.joinToString { it.value }}")
            }
        }
}

/**
 * What re-importing [source] does: delete what it created, return its records to never-imported, run
 * the normal import over them again and put the hand edits back. A preview for the confirmation dialog,
 * executed by [executeSourceRerun].
 */
data class SourceRerunPlan(
    /** The whole import or session: `Source.Csv(importId)`, `Source.Qif(importId)` or `Source.Api(sessionId)`. */
    val source: Source,
    /** Every transfer the source created, with the record it came from. */
    val createdTransfers: Map<TransferId, Source>,
    val tradeIds: Set<TradeId>,
    /** Accounts the source created; those the re-run leaves empty are deleted. */
    val createdAccountIds: Set<AccountId>,
    val affected: AffectedRecords,
    val manualEdits: List<ManualEdit>,
)

/** The outcome of [executeSourceRerun]; [rerun] is what the source's own import returned. */
data class SourceRerunResult<R>(
    val rerun: R,
    val deletedTransfers: Int,
    val deletedTrades: Int,
    /** Transfers from other sources the deleted ones had hidden as reconciled copies, shown again. */
    val unhiddenTransfers: Int,
    /** Records of other files matched against a deleted transfer, reset and re-run. */
    val affected: AffectedRecords,
    /**
     * Records of other files the re-import reset but couldn't re-run (their file's account or currency is
     * unknown, or its run failed). Import-all skips an applied file, so they wait for that file's re-import.
     */
    val notRerun: AffectedRecords,
    val reappliedEdits: List<ManualEdit>,
    /** Hand edits whose transfer the re-run didn't create again (its record is now a duplicate, or skipped). */
    val unmatchedEdits: List<ManualEdit>,
    val deletedEmptyAccounts: List<String>,
)

/**
 * Plans re-importing [source]: what it created (per `entity_source`, first revision), what other files
 * matched against that, and which of its transfers a person edited by hand.
 */
suspend fun planSourceRerun(
    source: Source,
    tradeIds: Set<TradeId>,
    createdAccountIds: Set<AccountId>,
    reads: RerunReads,
    onProgress: (suspend (Progress) -> Unit)? = null,
): SourceRerunPlan {
    val progress = PhasedProgress(onProgress)
    progress.emit(base = 0f, detail = "Finding what the import created")
    val created = reads.transferSourceRepository.getTransfersCreatedBy(source)
    progress.emit(base = PLAN_CREATED_SPAN, detail = "Checking other files for records matched against it")
    val affected =
        AffectedRecords.of(reads.transferSourceRepository.getImportRecordsMatchedTo(created.keys, tradeIds), excluding = source)
    val manualEditsBase = PLAN_CREATED_SPAN + PLAN_OTHER_FILES_SPAN
    return SourceRerunPlan(
        source = source,
        createdTransfers = created,
        tradeIds = tradeIds,
        createdAccountIds = createdAccountIds,
        affected = affected,
        manualEdits =
            captureManualEdits(created, reads, progress.sink(base = manualEditsBase, span = 1f - manualEditsBase)),
    )
}

private const val PLAN_CREATED_SPAN = 0.05f
private const val PLAN_OTHER_FILES_SPAN = 0.05f

/**
 * Re-imports per [plan]. One batch un-hides the partners the source's transfers had reconciled away,
 * deletes what it created and resets its own records ([resetRecords]) and the [AffectedRecords]. Then
 * [rerun] runs the source's normal import, [rerunAffected] runs the affected records again, the hand
 * edits are re-applied, and import-created accounts left empty are deleted.
 *
 * Account merges are not undone: nothing ties a merge to a source, and a person's merge must stay.
 */
@Suppress("LongParameterList")
suspend fun <R> ImportEngine.executeSourceRerun(
    plan: SourceRerunPlan,
    resetRecords: ImportBatch,
    reads: RerunReads,
    maintenance: Maintenance,
    rerunAffected: AffectedRecordsRerunner?,
    onProgress: (suspend (Progress) -> Unit)? = null,
    refreshViews: Boolean = true,
    rerun: suspend () -> R,
): SourceRerunResult<R> {
    val source = plan.source
    val unhideUpdates =
        reconciledPartnerUnhideUpdates(plan.createdTransfers.keys, reads.transferRelationshipRepository, reads.transactionRepository)

    val rerunAffectedRecords = if (plan.affected.isEmpty) null else rerunAffected?.prepare(plan.affected)

    val affectedResets = plan.affected.resetMutations()
    // The un-hide updates ride in the delete batch (the engine applies updates before deletes), and so
    // do the record resets: other files' links can't be found again once the transactions are gone.
    onProgress?.invoke(Progress("Removing what the import created"))
    import(
        resetRecords.copy(
            transfers =
                unhideUpdates +
                    plan.createdTransfers.keys.map { id ->
                        ImportTransfer(source = source, operation = ImportOperation.DELETE, existingId = id)
                    },
            trades =
                plan.tradeIds.map { id ->
                    ImportTradeIntent(
                        key = LocalTradeKey("reimport-delete-${id.id}"),
                        source = source,
                        operation = ImportOperation.DELETE,
                        existingId = id,
                    )
                },
            dedupePolicy = DedupePolicy.None,
            csvImportMutations = resetRecords.csvImportMutations + affectedResets.csvImportMutations,
            qifImportMutations = resetRecords.qifImportMutations + affectedResets.qifImportMutations,
        ),
        onProgress = onProgress?.let { sink -> { sink(it.copy(detail = "Removing what the import created")) } },
    )

    onProgress?.invoke(Progress("Re-importing"))
    val result = rerun()

    val notRerun =
        when {
            !plan.affected.isEmpty && rerunAffectedRecords == null -> {
                plan.affected
            }

            rerunAffectedRecords == null -> {
                AffectedRecords()
            }

            else -> {
                onProgress?.invoke(Progress("Re-linking other files"))
                rerunAffectedRecords(onProgress)
            }
        }

    onProgress?.invoke(Progress("Re-applying manual edits"))
    val (reapplied, unmatched) = reapplyManualEdits(plan, reads)

    onProgress?.invoke(Progress("Cleaning up empty accounts"))
    val deletedEmptyAccounts =
        deleteEmptyImportCreatedAccounts(
            createdAccountIds = plan.createdAccountIds,
            source = source,
            accountRepository = reads.accountRepository,
            tradeRepository = reads.tradeRepository,
        )

    if (refreshViews) {
        onProgress?.invoke(Progress("Refreshing views"))
        maintenance.refreshMaterializedViews()
    }

    return SourceRerunResult(
        rerun = result,
        deletedTransfers = plan.createdTransfers.size,
        deletedTrades = plan.tradeIds.size,
        unhiddenTransfers = unhideUpdates.size,
        affected = AffectedRecords(plan.affected.byFile - notRerun.byFile.keys),
        notRerun = notRerun,
        reappliedEdits = reapplied,
        unmatchedEdits = unmatched,
        deletedEmptyAccounts = deletedEmptyAccounts,
    )
}

/**
 * The hand edits re-importing each source would put back, without the rest of [planSourceRerun]. [created]
 * holds what each source created ([TransferSourceReadRepository.getTransfersCreatedBy]); the hand edits
 * among them all are looked up at once, so a "re-import all" costs one lookup rather than one per file.
 * [onProgress] hears each source's step in [created]'s order.
 */
suspend fun manualEditsOf(
    created: Map<Source, Map<TransferId, Source>>,
    reads: RerunReads,
    onProgress: (suspend (Source, Progress) -> Unit)? = null,
): Map<Source, List<ManualEdit>> {
    val edited = reads.transferSourceRepository.getHandEditedTransferIds(created.values.flatMap { it.keys })
    val current = if (edited.isEmpty()) emptyMap() else reads.transactionRepository.getTransactionsByIds(edited)
    return created.mapValues { (source, transfers) ->
        onProgress?.invoke(source, Progress(READING_MANUAL_EDITS))
        readManualEdits(transfers, edited.filterTo(mutableSetOf()) { it in transfers }, current, reads) { progress ->
            onProgress?.invoke(source, progress)
        }
    }
}

private suspend fun captureManualEdits(
    created: Map<TransferId, Source>,
    reads: RerunReads,
    onProgress: (suspend (Progress) -> Unit)?,
): List<ManualEdit> {
    onProgress?.invoke(Progress(READING_MANUAL_EDITS))
    val edited = reads.transferSourceRepository.getHandEditedTransferIds(created.keys)
    if (edited.isEmpty()) return emptyList()
    return readManualEdits(created, edited, reads.transactionRepository.getTransactionsByIds(edited), reads, onProgress)
}

// [edited] are those of [created] edited by hand, [current] (at least) their transfers as they are now.
private suspend fun readManualEdits(
    created: Map<TransferId, Source>,
    edited: Set<TransferId>,
    current: Map<TransferId, Transfer>,
    reads: RerunReads,
    onProgress: (suspend (Progress) -> Unit)?,
): List<ManualEdit> =
    edited.sortedBy { it.id }.withIndex().mapNotNull { (index, id) ->
        onProgress?.invoke(Progress.counted(READING_MANUAL_EDITS, index, edited.size))
        val transfer = current[id] ?: return@mapNotNull null
        manualEditOf(
            transfer = transfer,
            record = created.getValue(id),
            history = reads.auditRepository.getAuditHistoryForTransfer(id),
            sources = reads.transferSourceRepository.getSourcesForTransaction(id),
        )
    }

private const val READING_MANUAL_EDITS = "Reading manual edits"

/**
 * Reads a transfer's hand edits from its audit trail. An UPDATE audit row holds the values from before
 * its revision, so the values at revision r are the UPDATE row of revision r + 1, or the transfer itself
 * for the current revision. A field is a hand edit when the last revision that changed it was MANUAL.
 */
internal fun manualEditOf(
    transfer: Transfer,
    record: Source,
    history: List<TransferAuditEntry>,
    sources: List<SourceRecord>,
): ManualEdit? {
    val manualRevisions = sources.filter { it.source == Source.Manual }.map { it.revisionId }.toSet()
    if (manualRevisions.isEmpty()) return null

    val valuesAt = mutableMapOf(transfer.revisionId to transfer.values())
    history.forEach { entry ->
        when (entry.auditType) {
            AuditType.INSERT -> valuesAt.getOrPut(entry.revisionId) { entry.values() }
            AuditType.UPDATE -> valuesAt.getOrPut(entry.revisionId - 1) { entry.values() }
            AuditType.DELETE -> Unit
        }
    }
    val fields = TransferField.entries
    val lastChange = mutableMapOf<TransferField, Long>()
    valuesAt.keys.sorted().zipWithNext().forEach { (before, after) ->
        val old = valuesAt.getValue(before)
        val new = valuesAt.getValue(after)
        fields.filter { it.of(old) != it.of(new) }.forEach { lastChange[it] = after }
    }
    val manualFields = fields.filter { lastChange[it] in manualRevisions }

    // The import's view: each hand-edited field as it was just before its last edit.
    val now = transfer.values()
    val imported =
        manualFields.fold(now) { values, field ->
            valuesAt[lastChange.getValue(field) - 1]?.let { field.take(values, it) } ?: values
        }

    // Attribute changes are only audited outside import batches, so any on a MANUAL revision is a hand edit.
    val editedTypes =
        history
            .flatMap { it.attributeChanges }
            .filter { it.revisionId in manualRevisions }
            .map { it.attributeType }
            .distinctBy { it.id }
    val attributes =
        editedTypes.associateWith { type ->
            transfer.attributes
                .filter { it.attributeType.id == type.id }
                .map { NewAttribute(type.id, it.value, it.groupKey) }
        }

    if (manualFields.isEmpty() && attributes.isEmpty()) return null
    return ManualEdit(
        transferId = transfer.id,
        record = record,
        imported = imported,
        timestamp = now.timestamp.takeIf { TransferField.TIMESTAMP in manualFields },
        description = now.description.takeIf { TransferField.DESCRIPTION in manualFields },
        sourceAccountId = now.sourceAccountId.takeIf { TransferField.SOURCE in manualFields },
        targetAccountId = now.targetAccountId.takeIf { TransferField.TARGET in manualFields },
        amount = now.amount.takeIf { TransferField.AMOUNT in manualFields },
        attributes = attributes,
    )
}

private enum class TransferField(
    val of: (TransferValues) -> Any,
    /** [TransferValues] with this field taken from another. */
    val take: (TransferValues, TransferValues) -> TransferValues,
) {
    TIMESTAMP({ it.timestamp }, { to, from -> to.copy(timestamp = from.timestamp) }),
    DESCRIPTION({ it.description }, { to, from -> to.copy(description = from.description) }),
    SOURCE({ it.sourceAccountId }, { to, from -> to.copy(sourceAccountId = from.sourceAccountId) }),
    TARGET({ it.targetAccountId }, { to, from -> to.copy(targetAccountId = from.targetAccountId) }),
    AMOUNT({ it.amount }, { to, from -> to.copy(amount = from.amount) }),
}

/**
 * Finds each edited transfer's replacement, the transfer the re-run created from the same record, and
 * applies the edit there as a MANUAL update, so a later re-import keeps it too. A record that produced
 * several transfers (fee and pass-through legs) picks the one closest to [ManualEdit.imported].
 */
private suspend fun ImportEngine.reapplyManualEdits(
    plan: SourceRerunPlan,
    reads: RerunReads,
): Pair<List<ManualEdit>, List<ManualEdit>> {
    if (plan.manualEdits.isEmpty()) return emptyList<ManualEdit>() to emptyList()
    val byRecord =
        reads.transferSourceRepository
            .getTransfersCreatedBy(plan.source)
            .entries
            .groupBy({ it.value }, { it.key })
    val candidates = reads.transactionRepository.getTransactionsByIds(byRecord.values.flatten())
    val claimed = mutableSetOf<TransferId>()
    val reapplied = mutableListOf<ManualEdit>()
    val unmatched = mutableListOf<ManualEdit>()
    val updates = mutableListOf<ImportTransfer>()
    plan.manualEdits.forEach { edit ->
        val replacement =
            byRecord[edit.record]
                .orEmpty()
                .filter { it !in claimed }
                .mapNotNull { candidates[it] }
                .maxByOrNull { matchScore(edit.imported, it.values()) }
        if (replacement == null) {
            unmatched += edit
        } else {
            claimed += replacement.id
            reapplied += edit
            updates += edit.updateOf(replacement)
        }
    }
    if (updates.isNotEmpty()) import(ImportBatch.manualEdits(transfers = updates))
    return reapplied to unmatched
}

private fun matchScore(
    imported: TransferValues,
    candidate: TransferValues,
): Int = TransferField.entries.count { it.of(imported) == it.of(candidate) }

private fun ManualEdit.updateOf(replacement: Transfer): ImportTransfer {
    val editedTypeIds = attributes.keys.map { it.id }.toSet()
    return ImportTransfer(
        source = Source.Manual,
        operation = ImportOperation.UPDATE,
        existingId = replacement.id,
        // The engine updates the transfer row only when it gets the values; attribute-only edits send none.
        timestamp = if (changesFields) timestamp ?: replacement.timestamp else null,
        description = description ?: replacement.description,
        fromAccount = if (changesFields) AccountRef.Existing(sourceAccountId ?: replacement.sourceAccountId) else null,
        toAccount = if (changesFields) AccountRef.Existing(targetAccountId ?: replacement.targetAccountId) else null,
        amount = if (changesFields) amount ?: replacement.amount else null,
        deletedAttributeIds =
            replacement.attributes
                .filter { it.attributeType.id in editedTypeIds }
                .map { it.id }
                .toSet(),
        attributes = attributes.values.flatten(),
    )
}
