package com.moneymanager.importengineapi

import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.RelationshipReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.tasks.PhasedProgress
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.flow.first

/** The reads a re-import needs, whatever the source. */
class RerunReads(
    val transactionRepository: TransactionReadRepository,
    val transferSourceRepository: TransferSourceReadRepository,
    val relationshipRepository: RelationshipReadRepository,
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
     * it couldn't (their run failed). A file that can't be re-imported at all (its strategy, account or currency is
     * unknown) throws [RerunBlockedException] here, before anything is deleted.
     */
    suspend fun prepare(files: List<Source>): suspend (onProgress: (suspend (Progress) -> Unit)?) -> Set<Source>
}

/**
 * A re-import refused before it deleted anything, because these files it would take along can't be imported again: each
 * of [files] is a file name and why (its strategy is gone, or nothing says which account or currency it imports with).
 */
class RerunBlockedException(
    val files: List<String>,
) : IllegalStateException("Can't re-import ${files.joinToString()}, so nothing was re-imported")

/**
 * What re-importing [source] does: delete what it created, return its records to never-imported and run the normal
 * import over them again. Every record takes its old id back ([CsvImportMutation.Reserve], [ApiSessionMutation.Reserve]),
 * so what a person set on it (overrides, pins, links, trade attributes) holds without being replayed. The other files
 * that depend on what it deletes ([cascade]) are re-imported whole along with it. A preview for the confirmation dialog,
 * executed by [executeSourceRerun].
 */
data class SourceRerunPlan(
    /** The whole import or session: `Source.Csv(importId)` or `Source.Api(sessionId)`. */
    val source: Source,
    /** Every transfer the source created, with the record it came from. */
    val createdTransfers: Map<TransferId, Source>,
    val tradeIds: Set<TradeId>,
    /** Accounts the source created; those the re-run leaves empty are deleted. */
    val createdAccountIds: Set<AccountId>,
    /** Other files re-imported with the source, in the order they were imported. */
    val cascade: List<RerunFile> = emptyList(),
) {
    /** Everything the re-import deletes and runs again: the source, then its [cascade]. */
    val files: List<RerunFile> get() = listOf(RerunFile(source, createdTransfers, tradeIds, createdAccountIds)) + cascade
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
     * Files of the cascade whose transactions were deleted but whose run failed. (A file that couldn't run at all stops the
     * re-import before it deletes anything: [RerunBlockedException].) Import-all skips an applied file, so
     * they wait for that file's re-import.
     */
    val notRerun: List<Source>,
    val deletedEmptyAccounts: List<String>,
    /** Where the source's new records disagree with what a person set on them (#1430), for a person to resolve. */
    val conflicts: List<MaterializationConflict> = emptyList(),
    /**
     * What the re-import created, changed (field by field) and removed, with [conflicts] and the overrides it released: a
     * hand edit whose record no longer makes its transaction.
     */
    val plan: ImportPlan = ImportPlan(),
)

/** The outcome of [executeRerun]; [result] is what its run returned. */
data class RerunOutcome<R>(
    val result: R,
    val unhiddenTransfers: Int,
    val deletedEmptyAccounts: List<String>,
    val conflicts: List<MaterializationConflict> = emptyList(),
    val plan: ImportPlan = ImportPlan(),
)

/**
 * Plans re-importing [source]: what it created (per `entity_source`, first revision) and the other files that
 * depend on that ([RerunFile]), found in one query.
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
    return SourceRerunPlan(
        source = source,
        createdTransfers = created,
        tradeIds = tradeIds,
        createdAccountIds = createdAccountIds,
        cascade = rerunFiles(closure, reads),
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

private const val PLAN_CREATED_SPAN = 0.5f

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
        executeRerun(plan.files, resetRecords, reads, maintenance, onProgress, refreshViews = refreshViews) {
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
        deletedEmptyAccounts = outcome.deletedEmptyAccounts,
        conflicts = outcome.conflicts,
        plan = outcome.plan,
    )
}

/**
 * The one re-import: one batch un-hides the transfers the deleted ones had reconciled away, deletes what every one
 * of [files] created and reserves its ids for its records (plus [resetRecords], a session's own). Then [run] imports
 * the files again, each record taking its old id back, so nothing a person set on them is replayed: settling releases
 * the ids no record took back (reported in the [ImportPlan]), and the accounts the files created that are left empty
 * are deleted. The delete batch reports as a step of its own, from an empty bar to a full one.
 *
 * Account merges are not undone: nothing ties a merge to a source, and a person's merge must stay.
 */
@Suppress("LongParameterList")
suspend fun <R> ImportEngine.executeRerun(
    files: List<RerunFile>,
    resetRecords: ImportBatch,
    reads: RerunReads,
    maintenance: Maintenance,
    onProgress: (suspend (Progress) -> Unit)? = null,
    refreshViews: Boolean = true,
    run: suspend () -> R,
): RerunOutcome<R> {
    val deletedTransfers = files.flatMapTo(mutableSetOf()) { it.createdTransfers.keys }
    val before =
        TransactionSnapshot.read(
            files.flatMap { it.createdTransfers.entries }.associate { it.key to it.value },
            files.flatMap { reads.transferSourceRepository.getTradesCreatedBy(it.file).entries }.associate { it.key to it.value },
            reads.transactionRepository,
            reads.tradeRepository,
        )
    val unhideUpdates =
        reconciledPartnerUnhideUpdates(deletedTransfers, reads.relationshipRepository, reads.transactionRepository)
    val resets =
        files.mapNotNull { file -> (file.file as? Source.Csv)?.let { CsvImportMutation.Reserve(it.importId) } }
    val apiResets =
        files.mapNotNullTo(linkedSetOf()) { file -> (file.file as? Source.Api)?.let { ApiSessionMutation.Reserve(it.sessionId) } }

    // The un-hide updates ride in the delete batch (the engine applies updates before deletes), and so
    // do the record resets.
    onProgress?.invoke(Progress(REMOVING, fraction = 0f))
    val reserved =
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
                apiSessionMutations = resetRecords.apiSessionMutations + apiResets,
            ),
            onProgress = onProgress?.let { sink -> { sink(it.copy(detail = REMOVING)) } },
        ).reserved
    onProgress?.invoke(Progress("Removed what the import created", fraction = 1f))

    onProgress?.invoke(Progress("Re-importing"))
    // Applying a staged file deletes the accounts it left empty itself, so those the run removed count as cleaned up too.
    val created = files.flatMap { it.createdAccountIds }.distinct()
    val createdNames =
        created.mapNotNull { id ->
            reads.accountRepository
                .getAccountById(id)
                .first()
                ?.let { id to it.name }
        }
    val result = run()
    val emptiedByRun = createdNames.filter { (id, _) -> reads.accountRepository.getAccountById(id).first() == null }.map { it.second }

    // The records took their old ids back, so a person's overrides, pins and links on them still hold: settling releases
    // the ids no record took back and rebuilds the overridden ones (#1430). A hand edit on a released id is reported.
    val settled = if (reserved.ids.isEmpty()) MaterializationReport() else import(ImportBatch(settle = reserved)).materialization

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
            }.orEmpty() + emptiedByRun

    val after =
        TransactionSnapshot.read(
            files.flatMap { reads.transferSourceRepository.getTransfersCreatedBy(it.file).entries }.associate { it.key to it.value },
            files.flatMap { reads.transferSourceRepository.getTradesCreatedBy(it.file).entries }.associate { it.key to it.value },
            reads.transactionRepository,
            reads.tradeRepository,
        )

    if (refreshViews) {
        onProgress?.invoke(Progress("Refreshing views"))
        maintenance.refreshMaterializedViews()
    }

    return RerunOutcome(
        result = result,
        unhiddenTransfers = unhideUpdates.size,
        deletedEmptyAccounts = deletedEmptyAccounts,
        conflicts = settled.conflicts,
        plan = planFrom(before, after, settled.conflicts, settled.releasedOverridden),
    )
}

// The whole import or session a record belongs to.
private fun Source.wholeImport(): Source =
    when (this) {
        is Source.Csv -> Source.Csv(importId)
        is Source.Api -> Source.Api(sessionId)
        else -> this
    }

private const val REMOVING = "Removing what the import created"
