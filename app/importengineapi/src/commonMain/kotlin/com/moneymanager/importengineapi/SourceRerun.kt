package com.moneymanager.importengineapi

import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.RelationshipReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
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
 * A re-import refused before it deleted anything, because these files can't be imported again: each of [files] is a file
 * name and why (its strategy is gone, or nothing says which account or currency it imports with).
 */
class RerunBlockedException(
    val files: List<String>,
) : IllegalStateException("Can't re-import ${files.joinToString()}, so nothing was re-imported")

/**
 * What re-importing [source] does: delete what it created, return its records to never-imported and run the normal
 * import over them again. Every record takes its old id back ([CsvImportMutation.Reserve], [ApiSessionMutation.Reserve]),
 * so what a person set on it (overrides, pins, links, trade attributes) holds without being replayed. No other file is
 * re-run with it: what other records matched or reconciled against is matched again by the engine. A preview for the
 * confirmation dialog, executed by [executeSourceRerun].
 */
data class SourceRerunPlan(
    /** The whole import or session: `Source.Csv(importId)` or `Source.Api(sessionId)`. */
    val source: Source,
    /** Every transfer the source created, with the record it came from. */
    val createdTransfers: Map<TransferId, Source>,
    val tradeIds: Set<TradeId>,
    /** Accounts the source created; those the re-run leaves empty are deleted. */
    val createdAccountIds: Set<AccountId>,
) {
    /** What the re-import deletes and runs again. */
    val file: RerunFile get() = RerunFile(source, createdTransfers, tradeIds, createdAccountIds)
}

/**
 * A staged file or API session a re-import deletes what it created from and runs again, whole: a file's rows claim the
 * transactions they match one at a time, so a row run on its own can claim one its neighbour already explains.
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
    val deletedEmptyAccounts: List<String>,
    val conflicts: List<MaterializationConflict> = emptyList(),
    val plan: ImportPlan = ImportPlan(),
)

/** Plans re-importing [source]: what it created (per `entity_source`, first revision). */
suspend fun planSourceRerun(
    source: Source,
    tradeIds: Set<TradeId>,
    createdAccountIds: Set<AccountId>,
    reads: RerunReads,
    onProgress: (suspend (Progress) -> Unit)?,
): SourceRerunPlan {
    onProgress?.invoke(Progress("Finding what the import created"))
    return SourceRerunPlan(
        source = source,
        createdTransfers = reads.transferSourceRepository.getTransfersCreatedBy(source),
        tradeIds = tradeIds,
        createdAccountIds = createdAccountIds,
    )
}

/** Re-imports per [plan]: [executeRerun] over the source, where [rerun] runs its normal import. */
@Suppress("LongParameterList")
suspend fun <R> ImportEngine.executeSourceRerun(
    plan: SourceRerunPlan,
    resetRecords: ImportBatch,
    reads: RerunReads,
    maintenance: Maintenance,
    onProgress: (suspend (Progress) -> Unit)?,
    refreshViews: Boolean,
    rerun: suspend () -> R,
): SourceRerunResult<R> {
    val outcome = executeRerun(listOf(plan.file), resetRecords, reads, maintenance, onProgress, refreshViews = refreshViews, run = rerun)
    return SourceRerunResult(
        rerun = outcome.result,
        deletedTransfers = plan.createdTransfers.size,
        deletedTrades = plan.tradeIds.size,
        deletedEmptyAccounts = outcome.deletedEmptyAccounts,
        conflicts = outcome.conflicts,
        plan = outcome.plan,
    )
}

/**
 * The one re-import: one batch deletes what every one of [files] created and reserves its ids for its records (plus
 * [resetRecords], a session's own), and the engine shows again what the deleted transfers had reconciled away. Then
 * [run] imports the files again, each record taking its old id back, so nothing a person set on them is replayed:
 * settling releases the ids no record took back (reported in the [ImportPlan]), and the accounts the files created
 * that are left empty are deleted. The delete batch reports as a step of its own, from an empty bar to a full one.
 *
 * Account merges are not undone: nothing ties a merge to a source, and a person's merge must stay.
 */
@Suppress("LongParameterList")
suspend fun <R> ImportEngine.executeRerun(
    files: List<RerunFile>,
    resetRecords: ImportBatch,
    reads: RerunReads,
    maintenance: Maintenance,
    onProgress: (suspend (Progress) -> Unit)?,
    refreshViews: Boolean = true,
    run: suspend () -> R,
): RerunOutcome<R> {
    onProgress?.invoke(Progress("Reading what the files created, for the report", fraction = 0f, phase = RerunPhases.SETTING_ASIDE))
    val before =
        TransactionSnapshot.read(
            files.flatMap { it.createdTransfers.entries }.associate { it.key to it.value },
            files.flatMap { reads.transferSourceRepository.getTradesCreatedBy(it.file).entries }.associate { it.key to it.value },
            reads.transactionRepository,
            reads.tradeRepository,
        )
    val resets =
        files.mapNotNull { file -> (file.file as? Source.Csv)?.let { CsvImportMutation.Reserve(it.importId) } }
    val apiResets =
        files.mapNotNullTo(linkedSetOf()) { file -> (file.file as? Source.Api)?.let { ApiSessionMutation.Reserve(it.sessionId) } }

    // The record resets ride in the delete batch. What the deleted transfers had reconciled away is shown again by the
    // engine, once its link is left with one member.
    val reserved =
        import(
            resetRecords.copy(
                transfers =
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
            onProgress = onProgress?.let { sink -> { step -> settingAside(step)?.let { sink(it) } } },
        ).reserved
    onProgress?.invoke(
        Progress(
            "Set aside ${plural(
                files.sumOf { it.createdTransfers.size },
                "transaction",
            )} and ${plural(files.sumOf { it.tradeIds.size }, "trade")}",
            fraction = 1f,
            phase = RerunPhases.SETTING_ASIDE,
        ),
    )

    // Only marks where the run starts, for a view folding the phases into one bar: a run with phases of its own says them.
    onProgress?.invoke(Progress("Re-importing", fraction = 0f, phase = RerunPhases.REIMPORTING))
    // Applying a staged file deletes the accounts it left empty itself, so those the run removed count as cleaned up too.
    val created = files.flatMapTo(mutableSetOf()) { it.createdAccountIds }
    val createdNames =
        if (created.isEmpty()) {
            emptyMap()
        } else {
            reads.accountRepository
                .getAllAccounts()
                .first()
                .filter { it.id in created }
                .associate { it.id to it.name }
        }
    val result = run()
    val emptiedByRun =
        if (createdNames.isEmpty()) {
            emptyList()
        } else {
            val surviving =
                reads.accountRepository
                    .getAllAccounts()
                    .first()
                    .mapTo(mutableSetOf()) { it.id }
            createdNames.filterKeys { it !in surviving }.values.toList()
        }

    // The records took their old ids back, so a person's overrides, pins and links on them still hold: settling releases
    // the ids no record took back and rebuilds the overridden ones (#1430). A hand edit on a released id is reported. It runs
    // even when nothing was vacated: a reserved observation on a transaction another source keeps goes if its record didn't say it.
    val settled =
        import(
            ImportBatch(settle = reserved),
            onProgress = onProgress?.let { sink -> { step -> step.inPhase(RerunPhases.SETTLING)?.let { sink(it) } } },
        ).materialization

    val finishing = if (refreshViews) FINISHING_STEPS + 1 else FINISHING_STEPS
    onProgress?.invoke(Progress("Deleting the empty accounts the files created", fraction = 0f, phase = RerunPhases.FINISHING))
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

    onProgress?.invoke(Progress("Reading the transactions for the report", fraction = 1f / finishing, phase = RerunPhases.FINISHING))
    val after =
        TransactionSnapshot.read(
            files.flatMap { reads.transferSourceRepository.getTransfersCreatedBy(it.file).entries }.associate { it.key to it.value },
            files.flatMap { reads.transferSourceRepository.getTradesCreatedBy(it.file).entries }.associate { it.key to it.value },
            reads.transactionRepository,
            reads.tradeRepository,
        )

    if (refreshViews) {
        onProgress?.invoke(Progress("Refreshing views", fraction = 2f / finishing, phase = RerunPhases.FINISHING))
        maintenance.refreshMaterializedViews()
    }
    onProgress?.invoke(Progress("Finished", fraction = 1f, phase = RerunPhases.FINISHING))

    return RerunOutcome(
        result = result,
        deletedEmptyAccounts = deletedEmptyAccounts,
        conflicts = settled.conflicts,
        plan = planFrom(before, after, settled.conflicts, settled.releasedOverridden),
    )
}

private const val FINISHING_STEPS = 2

private fun plural(
    count: Int,
    noun: String,
): String = if (count == 1) "1 $noun" else "$count ${noun}s"

/** The engine's delete batch, said as what it does to a re-run: it sets the transactions aside, counted. */
private fun settingAside(step: Progress): Progress? {
    val total = step.total ?: return null
    return Progress(
        "Setting aside ${step.processed ?: 0} of $total transactions and trades",
        fraction = step.fraction ?: return null,
        phase = RerunPhases.SETTING_ASIDE,
    )
}

/** [this] engine step as a step of [phase]; null for the engine's steps that carry no fraction. */
fun Progress.inPhase(phase: Progress.Phase): Progress? = takeIf { it.fraction != null }?.copy(phase = phase)

/**
 * A re-run's phases, each filling the bar from empty: [executeRerun] reports setting aside, settling and finishing, and its
 * run the three between (a run that has none of its own is all [REIMPORTING]).
 */
object RerunPhases {
    const val COUNT = 6
    val SETTING_ASIDE = Progress.Phase(1, COUNT, "Setting aside what the files created")
    val PREPARING = Progress.Phase(2, COUNT, "Preparing files")
    val REIMPORTING = Progress.Phase(3, COUNT, "Re-importing files")
    val MATCHING = Progress.Phase(4, COUNT, "Matching records across sources")
    val SETTLING = Progress.Phase(5, COUNT, "Settling the transactions the files kept")
    val FINISHING = Progress.Phase(6, COUNT, "Finishing")
}
