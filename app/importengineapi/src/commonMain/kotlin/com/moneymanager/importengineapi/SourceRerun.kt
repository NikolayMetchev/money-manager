package com.moneymanager.importengineapi

import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.RelationshipId
import com.moneymanager.domain.model.RelationshipMember
import com.moneymanager.domain.model.RelationshipRole
import com.moneymanager.domain.model.RelationshipType
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferAuditEntry
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.RelationshipReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.tasks.PhasedProgress
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

/** The reads a re-import needs, whatever the source. */
class RerunReads(
    val transactionRepository: TransactionReadRepository,
    val transferSourceRepository: TransferSourceReadRepository,
    val relationshipRepository: RelationshipReadRepository,
    val auditRepository: AuditReadRepository,
    val accountRepository: AccountReadRepository,
    val csvImportRepository: CsvImportReadRepository,
    /** Null for a source that never creates trades (QIF). */
    val tradeRepository: TradeReadRepository?,
)

/**
 * Rows of other staged files that were matched (DUPLICATE or UPDATED) against some transactions, found from
 * their provenance, grouped by file.
 */
data class AffectedRecords(
    /** Each file's records, keyed by the whole file: a [Source.Csv] with no row. */
    val byFile: Map<Source, List<Long>> = emptyMap(),
) {
    val isEmpty: Boolean get() = byFile.isEmpty()
    val count: Int get() = byFile.values.sumOf { it.size }

    companion object {
        /** Groups [records] (each a staged row) by file, leaving out [excluding]'s own. */
        fun of(
            records: List<Source>,
            excluding: Source? = null,
        ): AffectedRecords =
            AffectedRecords(
                records
                    .mapNotNull { record ->
                        when (record) {
                            is Source.Csv -> record.rowIndex?.let { Source.Csv(record.importId) to it }
                            else -> null
                        }
                    }.filter { (file, _) -> file != excluding?.wholeImport() }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, indexes) -> indexes.distinct().sorted() },
            )
    }
}

/**
 * Re-imports the other files a re-import takes along ([SourceRerunPlan.cascade]), each whole and as it was
 * last imported; supplied by a caller that can run staged files.
 */
fun interface CascadeRerunner {
    /**
     * Called before the re-import deletes anything, while each file still reads as it was imported; the
     * returned function imports [files] again, in order, after the source's own re-run, and returns the files
     * it couldn't (their strategy, account or currency is unknown, or their run failed).
     */
    suspend fun prepare(files: List<Source>): suspend (onProgress: (suspend (Progress) -> Unit)?) -> Set<Source>
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
 * Trade attributes a person set or removed by hand on a trade an import created. A re-import puts them
 * back on the trade the re-run creates from the same [record].
 */
data class ManualTradeEdit(
    val tradeId: TradeId,
    val record: Source,
    /** Each hand-edited type with the trade's value of it (null: removed by hand). */
    val attributes: Map<AttributeTypeId, String?>,
) {
    fun describe(): String = "Hand-set attributes of trade ${tradeId.id}"
}

/**
 * A relationship a person made (its provenance is [Source.Manual]) with a member a re-import deletes. The
 * re-import rebuilds it over the transactions the re-run creates from the deleted members' records.
 */
data class ManualRelationship(
    val id: RelationshipId,
    val type: RelationshipType,
    val members: List<Member>,
) {
    /**
     * [transactionId] as it is before the re-import. A member the re-import deletes is found again by the
     * [record] that created it; a transfer by the one closest to [values] when the record made several.
     */
    data class Member(
        val transactionId: TransactionId,
        val role: RelationshipRole,
        val record: Source? = null,
        val values: TransferValues? = null,
    )

    fun describe(): String = "\"${type.name}\" link between ${members.size} transactions"
}

/** The hand-made links and trade attributes a re-import put back, and those it couldn't. */
data class ReappliedLinks(
    val reapplied: Int = 0,
    /** One line per link or trade edit whose transaction the re-run didn't create again. */
    val notReapplied: List<String> = emptyList(),
) {
    operator fun plus(other: ReappliedLinks) = ReappliedLinks(reapplied + other.reapplied, notReapplied + other.notReapplied)

    /** The counts for a one-line re-import summary, each clause starting with [separator]; empty when there are none. */
    fun summary(separator: String): String =
        buildString {
            if (reapplied >
                0
            ) {
                append(
                    "$separator$reapplied manual link${if (reapplied == 1) "" else "s"} or trade edit${if (reapplied == 1) "" else "s"} re-applied",
                )
            }
            if (notReapplied.isNotEmpty()) append("$separator${notReapplied.size} manual links or trade edits not re-applied")
        }
}

/**
 * What re-importing [source] does: delete what it created, return its records to never-imported, run
 * the normal import over them again and put the hand edits back. The other files that depend on what it
 * deletes ([cascade]) are re-imported whole along with it. A preview for the confirmation dialog, executed by
 * [executeSourceRerun].
 */
data class SourceRerunPlan(
    /** The whole import or session: `Source.Csv(importId)` or `Source.Api(sessionId)`. */
    val source: Source,
    /** Every transfer the source created, with the record it came from. */
    val createdTransfers: Map<TransferId, Source>,
    val tradeIds: Set<TradeId>,
    /** Accounts the source created; those the re-run leaves empty are deleted. */
    val createdAccountIds: Set<AccountId>,
    /** Hand edits of what the source and its [cascade] created. */
    val manualEdits: List<ManualEdit>,
    /** Other files re-imported with the source, in the order they were imported. */
    val cascade: List<RerunFile> = emptyList(),
    val manualTradeEdits: List<ManualTradeEdit> = emptyList(),
    /** Hand-made relationships with a member among what the re-import deletes. */
    val manualRelationships: List<ManualRelationship> = emptyList(),
) {
    /** Everything the re-import deletes and runs again: the source, then its [cascade]. */
    val files: List<RerunFile> get() = listOf(RerunFile(source, createdTransfers, tradeIds, createdAccountIds)) + cascade

    val links: RerunLinks get() = RerunLinks(manualTradeEdits, manualRelationships)
}

/**
 * A staged file or API session a re-import deletes what it created from and runs again. Besides the files a person
 * picks, every file that depends on what they create is re-imported too, transitively
 * ([TransferSourceReadRepository.getRerunClosure]): a file with a row matched against a deleted transaction (left
 * alone, the row would point at nothing and its movement would be lost), or one that created a transfer a deleted one
 * had reconciled away or lent an end to (`ImportDeduper.otherSideOf`): un-hiding that alone isn't enough, as the re-run
 * never looks at it again and would count the movement twice.
 *
 * Re-importing whole files, never single rows, matters: a file's rows claim the transactions they match one at a
 * time, so a row run on its own can claim one its neighbour already explains.
 */
data class RerunFile(
    val file: Source,
    /** Every transfer the file created, with the row it came from. */
    val createdTransfers: Map<TransferId, Source>,
    val tradeIds: Set<TradeId>,
    val createdAccountIds: Set<AccountId>,
)

/** The hand-set trade attributes and hand-made relationships a re-import puts back. */
data class RerunLinks(
    val tradeEdits: List<ManualTradeEdit> = emptyList(),
    val relationships: List<ManualRelationship> = emptyList(),
)

/** The outcome of [executeSourceRerun]; [rerun] is what the source's own import returned. */
data class SourceRerunResult<R>(
    val rerun: R,
    val deletedTransfers: Int,
    val deletedTrades: Int,
    /** Transfers from other sources the deleted ones had hidden as reconciled copies, shown again. */
    val unhiddenTransfers: Int,
    /** Other files re-imported whole along with the source ([SourceRerunPlan.cascade]). */
    val cascaded: List<Source>,
    /**
     * Files of the cascade whose transactions were deleted but that couldn't be imported again (their
     * strategy, account or currency is unknown, or their run failed). Import-all skips an applied file, so
     * they wait for that file's re-import.
     */
    val notRerun: List<Source>,
    val reappliedEdits: List<ManualEdit>,
    /** Hand edits whose transfer the re-run didn't create again (its record is now a duplicate, or skipped). */
    val unmatchedEdits: List<ManualEdit>,
    val deletedEmptyAccounts: List<String>,
    /** Hand-made relationships and hand-set trade attributes put back on the re-created transactions. */
    val reappliedLinks: ReappliedLinks = ReappliedLinks(),
)

/** The outcome of [executeRerun]; [result] is what its run returned. */
data class RerunOutcome<R>(
    val result: R,
    val unhiddenTransfers: Int,
    val reappliedEdits: List<ManualEdit>,
    val unmatchedEdits: List<ManualEdit>,
    val reappliedLinks: ReappliedLinks,
    val deletedEmptyAccounts: List<String>,
)

/**
 * Plans re-importing [source]: what it created (per `entity_source`, first revision), the other files that
 * depend on that ([RerunFile]), found in one query, and which of their transactions a person edited by hand.
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
    progress.emit(base = PLAN_CREATED_SPAN, detail = "Finding other files that depend on it")
    val closure =
        when (source) {
            is Source.Csv -> reads.transferSourceRepository.getRerunClosure(setOf(source.importId)) - source.importId
            is Source.Api -> reads.transferSourceRepository.getRerunClosure(emptySet(), setOf(source.sessionId))
            else -> emptySet()
        }
    val cascade = rerunFiles(closure, reads)
    val own = RerunFile(source, created, tradeIds, createdAccountIds)
    val links = captureRerunLinks(listOf(own) + cascade, reads)
    val manualEditsBase = PLAN_CREATED_SPAN + PLAN_OTHER_FILES_SPAN
    return SourceRerunPlan(
        source = source,
        createdTransfers = created,
        tradeIds = tradeIds,
        createdAccountIds = createdAccountIds,
        manualEdits =
            captureManualEdits(
                created + cascade.flatMap { it.createdTransfers.entries }.associate { it.key to it.value },
                reads,
                progress.sink(base = manualEditsBase, span = 1f - manualEditsBase),
            ),
        cascade = cascade,
        manualTradeEdits = links.tradeEdits,
        manualRelationships = links.relationships,
    )
}

/** [imports] as [RerunFile]s, in the order they were imported: the order a re-import runs them in. */
suspend fun rerunFiles(
    imports: Collection<CsvImportId>,
    reads: RerunReads,
): List<RerunFile> =
    imports
        .map { importId ->
            val file = Source.Csv(importId)
            reads.csvImportRepository
                .getImport(importId)
                .first()
                ?.importTimestamp to
                RerunFile(
                    file = file,
                    createdTransfers = reads.transferSourceRepository.getTransfersCreatedBy(file),
                    tradeIds = reads.csvImportRepository.getTradeIdsCreatedByImport(importId),
                    createdAccountIds = reads.csvImportRepository.getAccountsCreatedByImport(importId),
                )
        }.sortedWith(compareBy(nullsLast()) { it.first })
        .map { it.second }

/** The hand-set trade attributes and hand-made relationships over what re-importing [files] deletes. */
suspend fun captureRerunLinks(
    files: List<RerunFile>,
    reads: RerunReads,
): RerunLinks {
    val createdTrades =
        files
            .filter { it.tradeIds.isNotEmpty() }
            .flatMap { file ->
                reads.transferSourceRepository
                    .getTradesCreatedBy(file.file)
                    .filterKeys { it in file.tradeIds }
                    .entries
            }.associate { it.key to it.value }
    val tradeEdits =
        reads.tradeRepository
            ?.getHandSetAttributes(createdTrades.keys)
            .orEmpty()
            .map { (id, attributes) -> ManualTradeEdit(id, createdTrades.getValue(id), attributes) }
    val createdTransfers = files.flatMap { it.createdTransfers.entries }.associate { it.key to it.value }
    return RerunLinks(tradeEdits, captureManualRelationships(createdTransfers, createdTrades, reads))
}

/** The hand-made relationships over any of [transfers] or [trades] (what a re-import may delete), with each member's record. */
private suspend fun captureManualRelationships(
    transfers: Map<TransferId, Source>,
    trades: Map<TradeId, Source>,
    reads: RerunReads,
): List<ManualRelationship> {
    val relationships = reads.relationshipRepository.getHandMadeByTransactions(transfers.keys + trades.keys)
    if (relationships.isEmpty()) return emptyList()
    val recordOf = transfers.mapKeys { it.key.id } + trades.mapKeys { it.key.id }
    val deletedTransferMembers =
        relationships.flatMap { it.members }.mapNotNull { (it.transactionId as? TransferId)?.takeIf { id -> id in transfers } }
    val current = reads.transactionRepository.getTransactionsByIds(deletedTransferMembers.distinct())
    return relationships.map { relationship ->
        ManualRelationship(
            id = relationship.id,
            type = relationship.relationshipType,
            members =
                relationship.members.map { member ->
                    ManualRelationship.Member(
                        transactionId = member.transactionId,
                        role = member.role,
                        record = recordOf[member.transactionId.id],
                        values = (member.transactionId as? TransferId)?.let { current[it]?.values() },
                    )
                },
        )
    }
}

private const val PLAN_CREATED_SPAN = 0.05f
private const val PLAN_OTHER_FILES_SPAN = 0.05f

/**
 * Re-imports per [plan]: [executeRerun] over the source and its cascade, where [rerun] runs the source's normal
 * import and [cascadeRerunner] then imports the cascade's files again, in order.
 */
@Suppress("LongParameterList")
suspend fun <R> ImportEngine.executeSourceRerun(
    plan: SourceRerunPlan,
    resetRecords: ImportBatch,
    reads: RerunReads,
    maintenance: Maintenance,
    cascadeRerunner: CascadeRerunner,
    onProgress: (suspend (Progress) -> Unit)? = null,
    refreshViews: Boolean = true,
    rerun: suspend () -> R,
): SourceRerunResult<R> {
    val cascadeFiles = plan.cascade.map { it.file }
    // Prepared before anything is deleted, while each file still reads as it was imported.
    val rerunCascade = if (cascadeFiles.isEmpty()) null else cascadeRerunner.prepare(cascadeFiles)
    val outcome =
        executeRerun(plan.files, plan.manualEdits, plan.links, resetRecords, reads, maintenance, onProgress, refreshViews = refreshViews) {
            val result = rerun()
            val notRerun =
                rerunCascade
                    ?.let {
                        onProgress?.invoke(Progress("Re-importing the files that depend on it"))
                        it(onProgress)
                    }.orEmpty()
            result to notRerun
        }
    val (result, notRerun) = outcome.result
    return SourceRerunResult(
        rerun = result,
        deletedTransfers = plan.createdTransfers.size,
        deletedTrades = plan.tradeIds.size,
        unhiddenTransfers = outcome.unhiddenTransfers,
        cascaded = cascadeFiles.filterNot { it in notRerun },
        notRerun = cascadeFiles.filter { it in notRerun },
        reappliedEdits = outcome.reappliedEdits,
        unmatchedEdits = outcome.unmatchedEdits,
        deletedEmptyAccounts = outcome.deletedEmptyAccounts,
        reappliedLinks = outcome.reappliedLinks,
    )
}

/**
 * The one re-import: one batch un-hides the transfers the deleted ones had reconciled away, deletes what every one
 * of [files] created and returns every staged file's records to never-imported (plus [resetRecords], a session's
 * own). Then [run] imports the files again, the hand edits ([manualEdits], [links]) are put back, and the accounts
 * the files created that are left empty are deleted.
 * [onRemovalProgress] follows the delete batch (by default [onProgress]), so a front end can show it as a bar of its own
 * that stays full once the batch is done.
 *
 * Account merges are not undone: nothing ties a merge to a source, and a person's merge must stay.
 */
@Suppress("LongParameterList")
suspend fun <R> ImportEngine.executeRerun(
    files: List<RerunFile>,
    manualEdits: List<ManualEdit>,
    links: RerunLinks,
    resetRecords: ImportBatch,
    reads: RerunReads,
    maintenance: Maintenance,
    onProgress: (suspend (Progress) -> Unit)? = null,
    onRemovalProgress: (suspend (Progress) -> Unit)? = onProgress,
    refreshViews: Boolean = true,
    run: suspend () -> R,
): RerunOutcome<R> {
    val deletedTransfers = files.flatMapTo(mutableSetOf()) { it.createdTransfers.keys }
    val deletedTrades = files.flatMapTo(mutableSetOf()) { it.tradeIds }
    val unhideUpdates =
        reconciledPartnerUnhideUpdates(deletedTransfers, reads.relationshipRepository, reads.transactionRepository)
    val resets =
        files.mapNotNull { file -> (file.file as? Source.Csv)?.let { CsvImportMutation.ResetRowStatuses(it.importId, null) } }

    // The un-hide updates ride in the delete batch (the engine applies updates before deletes), and so
    // do the record resets.
    onRemovalProgress?.invoke(Progress(REMOVING, fraction = 0f))
    import(
        resetRecords.copy(
            transfers =
                unhideUpdates +
                    files.flatMap { file ->
                        file.createdTransfers.keys.map { id ->
                            ImportTransfer(source = file.file, operation = ImportOperation.DELETE, existingId = id)
                        }
                    },
            trades =
                files.flatMap { file ->
                    file.tradeIds.map { id ->
                        ImportTradeIntent(
                            key = LocalTradeKey("reimport-delete-${id.id}"),
                            source = file.file,
                            operation = ImportOperation.DELETE,
                            existingId = id,
                        )
                    }
                },
            dedupePolicy = DedupePolicy.None,
            csvImportMutations = resetRecords.csvImportMutations + resets,
        ),
        onProgress = onRemovalProgress?.let { sink -> { sink(it.copy(detail = REMOVING)) } },
    )
    onRemovalProgress?.invoke(Progress("Removed what the import created", fraction = 1f))

    onProgress?.invoke(Progress("Re-importing"))
    val result = run()

    onProgress?.invoke(Progress("Re-applying manual edits"))
    val deletedIds = deletedTransfers.mapTo(mutableSetOf()) { it.id } + deletedTrades.map { it.id }
    val reapplication =
        reapplyManualEdits(
            manualEdits = manualEdits,
            tradeEdits = links.tradeEdits,
            relationships = links.relationships.filter { r -> r.members.any { it.transactionId.id in deletedIds } },
            deletedIds = deletedIds,
            reads = reads,
        )

    onProgress?.invoke(Progress("Cleaning up empty accounts"))
    val deletedEmptyAccounts =
        files
            .firstOrNull()
            ?.let { first ->
                deleteEmptyImportCreatedAccounts(
                    createdAccountIds = files.flatMap { it.createdAccountIds },
                    source = first.file,
                    accountRepository = reads.accountRepository,
                    tradeRepository = reads.tradeRepository,
                )
            }.orEmpty()

    if (refreshViews) {
        onProgress?.invoke(Progress("Refreshing views"))
        maintenance.refreshMaterializedViews()
    }

    return RerunOutcome(
        result = result,
        unhiddenTransfers = unhideUpdates.size,
        reappliedEdits = reapplication.reapplied,
        unmatchedEdits = reapplication.unmatched,
        reappliedLinks = reapplication.links,
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

private class Reapplication(
    val reapplied: List<ManualEdit>,
    val unmatched: List<ManualEdit>,
    val links: ReappliedLinks,
)

/**
 * The transactions a re-run created, by the record that created them, and which deleted transaction each
 * one replaces. A record that produced several transfers (fee and pass-through legs) gives each deleted one
 * the replacement closest to its values; each replacement replaces at most one.
 */
private class Replacements(
    private val transfersByRecord: Map<Source, List<TransferId>>,
    private val tradesByRecord: Map<Source, List<TradeId>>,
    val transfers: Map<TransferId, Transfer>,
) {
    private val newTransfer = mutableMapOf<TransferId, Transfer>()
    private val newTrade = mutableMapOf<TradeId, TradeId>()
    private val claimed = mutableSetOf<Long>()

    fun transfer(
        old: TransferId,
        record: Source,
        values: TransferValues,
    ): Transfer? =
        newTransfer[old] ?: transfersByRecord[record]
            .orEmpty()
            .filter { it.id !in claimed }
            .mapNotNull { transfers[it] }
            .maxByOrNull { matchScore(values, it.values()) }
            ?.also {
                claimed += it.id.id
                newTransfer[old] = it
            }

    fun trade(
        old: TradeId,
        record: Source,
    ): TradeId? =
        newTrade[old] ?: tradesByRecord[record]
            .orEmpty()
            .firstOrNull { it.id !in claimed }
            ?.also {
                claimed += it.id
                newTrade[old] = it
            }

    companion object {
        suspend fun of(
            records: Collection<Source>,
            reads: RerunReads,
        ): Replacements {
            val imports = records.map { it.wholeImport() }.distinct()
            val transfers =
                imports
                    .flatMap {
                        reads.transferSourceRepository
                            .getTransfersCreatedBy(
                                it,
                            ).entries
                    }.groupBy({ it.value }, { it.key })
            val trades = imports.flatMap { reads.transferSourceRepository.getTradesCreatedBy(it).entries }.groupBy({ it.value }, { it.key })
            val current =
                if (transfers.isEmpty()) {
                    emptyMap()
                } else {
                    reads.transactionRepository.getTransactionsByIds(
                        transfers.values.flatten(),
                    )
                }
            return Replacements(transfers, trades, current)
        }
    }
}

/**
 * Applies the hand edits to the transactions the re-run created from the same records, as MANUAL writes so
 * a later re-import keeps them too: each edited transfer's fields and attributes, each edited trade's
 * attributes, and each hand-made relationship rebuilt over its members' replacements ([deletedIds] are the
 * members that need one). A relationship is rebuilt only when every deleted member was created again.
 */
private suspend fun ImportEngine.reapplyManualEdits(
    manualEdits: List<ManualEdit>,
    tradeEdits: List<ManualTradeEdit>,
    relationships: List<ManualRelationship>,
    deletedIds: Set<Long>,
    reads: RerunReads,
): Reapplication {
    if (manualEdits.isEmpty() && tradeEdits.isEmpty() && relationships.isEmpty()) {
        return Reapplication(emptyList(), emptyList(), ReappliedLinks())
    }
    val replacements =
        Replacements.of(
            manualEdits.map { it.record } + tradeEdits.map { it.record } +
                relationships.flatMap { r ->
                    r.members.mapNotNull { it.record }
                },
            reads,
        )

    val reapplied = mutableListOf<ManualEdit>()
    val unmatched = mutableListOf<ManualEdit>()
    val updates = mutableListOf<ImportTransfer>()
    // Transfer edits first: a relationship member that was also edited is matched on its imported values.
    manualEdits.forEach { edit ->
        val replacement = replacements.transfer(edit.transferId, edit.record, edit.imported)
        if (replacement == null) {
            unmatched += edit
        } else {
            reapplied += edit
            updates += edit.updateOf(replacement)
        }
    }

    var linksReapplied = 0
    val linksNotReapplied = mutableListOf<String>()
    val tradeMutations = mutableListOf<TradeAttributeMutation>()
    tradeEdits.forEach { edit ->
        val replacement = replacements.trade(edit.tradeId, edit.record)
        if (replacement == null) {
            linksNotReapplied += edit.describe()
        } else {
            linksReapplied++
            edit.attributes.forEach { (typeId, value) ->
                tradeMutations +=
                    if (value ==
                        null
                    ) {
                        TradeAttributeMutation.Remove(replacement, typeId)
                    } else {
                        TradeAttributeMutation.Set(replacement, typeId, value)
                    }
            }
        }
    }

    val relationshipMutations = mutableListOf<RelationshipMutation>()
    relationships.forEach { relationship ->
        val members =
            relationship.members.map { member ->
                val id = member.transactionId
                val record = member.record
                val replacement: TransactionId? =
                    when {
                        id.id !in deletedIds -> id
                        record == null -> null
                        id is TransferId -> member.values?.let { replacements.transfer(id, record, it)?.id }
                        id is TradeId -> replacements.trade(id, record)
                        else -> null
                    }
                replacement?.let { RelationshipMember(it, member.role) }
            }
        if (members.any { it == null }) {
            linksNotReapplied += relationship.describe()
        } else {
            linksReapplied++
            // What's left of the old one (a member the re-import kept) goes, so the rebuilt one is the only copy.
            relationshipMutations += RelationshipMutation.Delete(relationship.id)
            relationshipMutations += RelationshipMutation.Create(relationship.type.id, members.filterNotNull(), Source.Manual)
        }
    }

    if (updates.isNotEmpty() || tradeMutations.isNotEmpty() || relationshipMutations.isNotEmpty()) {
        import(
            ImportBatch
                .manualEdits(transfers = updates)
                .copy(tradeAttributeMutations = tradeMutations, relationshipMutations = relationshipMutations),
        )
    }
    return Reapplication(reapplied, unmatched, ReappliedLinks(linksReapplied, linksNotReapplied))
}

// The whole import or session a record belongs to.
private fun Source.wholeImport(): Source =
    when (this) {
        is Source.Csv -> Source.Csv(importId)
        is Source.Api -> Source.Api(sessionId)
        else -> this
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

private const val REMOVING = "Removing what the import created"
