package com.moneymanager.csvimporter

import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.RelationshipReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.importengineapi.AffectedRecords
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.deleteEmptyImportCreatedAccounts
import com.moneymanager.importengineapi.reconciledPartnerUnhideUpdates
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.flow.first

/** Another file whose rows were matched (DUPLICATE/UPDATED) against a transaction the unimport deletes. */
data class CsvUnimportAffectedFile(
    val importId: CsvImportId,
    val fileName: String,
    /** Its rows that point at a deleted transaction; the unimport resets them to never-imported. */
    val rowIndexes: List<Long>,
)

/**
 * What unimporting a CSV file will do — a preview for the confirmation dialog, executed verbatim by
 * [executeCsvUnimport].
 */
data class CsvUnimportPlan(
    val importId: CsvImportId,
    /** The transfers the unimport deletes (fee and pass-through legs included): those nothing outside the file still observes. */
    val transferIds: Set<TransferId>,
    val tradeIds: Set<TradeId>,
    /** Accounts the file auto-created; those left with no activity are deleted afterwards. */
    val createdAccountIds: Set<AccountId>,
    val affectedFiles: List<CsvUnimportAffectedFile>,
)

/** Outcome of [executeCsvUnimport]. */
data class CsvUnimportResult(
    val deletedTransfers: Int,
    val deletedTrades: Int,
    val deletedEmptyAccounts: List<String>,
    /** Transfers from other sources that the deleted ones had hidden as reconciled copies, now shown again. */
    val unhiddenTransfers: Int,
    val affectedFiles: List<CsvUnimportAffectedFile>,
)

/**
 * Works out what unimporting [importId] removes. Unimport is a sync of the file with no records: it withdraws the
 * file's observations, and a transaction goes only once nothing else observes it. So it deletes the transactions the
 * file created, or observes and another file or the match created, that no other file's or API session's record, no
 * person and no leg group outside the file still observes. One still observed stays, rebuilt from what the others say,
 * and their rows keep it. A transaction a person, an API session or a reconciliation copy created is never the file's.
 * Rows in other files that point at a transaction about to be deleted without observing it are listed so they can be
 * reset: their movement is gone with it until that file is re-imported.
 */
suspend fun planCsvUnimport(
    importId: CsvImportId,
    csvImportRepository: CsvImportReadRepository,
    transferSourceRepository: TransferSourceReadRepository,
): CsvUnimportPlan = planWithdrawal(importId, setOf(importId), emptySet(), csvImportRepository, transferSourceRepository)

/** [planCsvUnimport] for [importId] withdrawn along with the files [withdrawing], leaving out the transactions in [claimed]. */
private suspend fun planWithdrawal(
    importId: CsvImportId,
    withdrawing: Set<CsvImportId>,
    claimed: Set<TransactionId>,
    csvImportRepository: CsvImportReadRepository,
    transferSourceRepository: TransferSourceReadRepository,
): CsvUnimportPlan {
    val candidates: Set<TransactionId> =
        csvImportRepository.getTransferIdsCreatedByImport(importId) +
            csvImportRepository.getTradeIdsCreatedByImport(importId) +
            csvImportRepository.getTransactionsObservedByImport(importId) - claimed
    val deleted = candidates - csvImportRepository.getTransactionsObservedOutsideImports(withdrawing, candidates)
    val transferIds = deleted.filterIsInstanceTo(mutableSetOf<TransferId>())
    val tradeIds = deleted.filterIsInstanceTo(mutableSetOf<TradeId>())
    val referencing =
        AffectedRecords
            .of(transferSourceRepository.getImportRecordsMatchedTo(transferIds, tradeIds), excluding = Source.Csv(importId))
            .byFile
            .mapNotNull { (file, rows) -> (file as? Source.Csv)?.let { it.importId to rows } }
            .toMap()
    val fileNames =
        if (referencing.isEmpty()) {
            emptyMap()
        } else {
            csvImportRepository.getAllImports().first().associate { it.id to it.originalFileName }
        }
    return CsvUnimportPlan(
        importId = importId,
        transferIds = transferIds,
        tradeIds = tradeIds,
        createdAccountIds = csvImportRepository.getAccountsCreatedByImport(importId),
        affectedFiles =
            referencing
                .map { (id, rows) -> CsvUnimportAffectedFile(id, fileNames[id] ?: id.toString(), rows) }
                .sortedBy { it.fileName },
    )
}

/**
 * Unimports a file per [plan]: deletes the transactions only it observed, returns it (and the rows of any
 * [CsvUnimportPlan.affectedFiles]) to the never-imported state, marks it ignored, un-hides transfers
 * from other sources that its transfers had reconciled away, and deletes the accounts it created that
 * are left empty. Its strategy applications are deleted too; the application audit trail keeps the
 * history, which is how the UI knows the file was unimported. A transaction something else still observes loses only
 * the file's observations, and is rebuilt from what is left.
 *
 * Account merges are not reversed: nothing ties a merge to a file, and one the user performed must not
 * be silently undone.
 */
@Suppress("LongParameterList")
suspend fun executeCsvUnimport(
    plan: CsvUnimportPlan,
    accountRepository: AccountReadRepository,
    transactionRepository: TransactionReadRepository,
    relationshipRepository: RelationshipReadRepository,
    tradeRepository: TradeReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onProgress: (suspend (Progress) -> Unit)? = null,
    refreshViews: Boolean = true,
): CsvUnimportResult {
    val source = Source.Csv(plan.importId)
    val unhideUpdates = reconciledPartnerUnhideUpdates(plan.transferIds, relationshipRepository, transactionRepository)

    // The deletes can be re-derived from entity_source, so a failure before the file reset leaves a state a repeated
    // unimport recovers from; the file only reads as unimported once they succeeded. The un-hide updates share the delete
    // batch (the engine applies updates before deletes), and so do the other files' row resets, which can't be found
    // again once the transactions are gone.
    onProgress?.invoke(Progress("Removing the file's transactions"))
    importEngine.import(
        ImportBatch(
            transfers =
                unhideUpdates +
                    plan.transferIds.map { id ->
                        ImportTransfer(source = source, operation = ImportOperation.DELETE, existingId = id)
                    },
            trades =
                plan.tradeIds.map { id ->
                    ImportTradeIntent(
                        key = LocalTradeKey("unimport-delete-${id.id}"),
                        source = source,
                        operation = ImportOperation.DELETE,
                        existingId = id,
                    )
                },
            dedupePolicy = DedupePolicy.None,
            csvImportMutations = plan.affectedFiles.map { CsvImportMutation.ResetRowStatuses(it.importId, it.rowIndexes) },
        ),
    )

    importEngine.import(
        ImportBatch(
            csvImportMutations =
                listOf(
                    CsvImportMutation.ResetToUnimported(plan.importId),
                    CsvImportMutation.SetIgnored(plan.importId, ignored = true),
                ),
            // What the file's rows were matched or linked with is matched again without them, as a fresh import of the
            // others would.
            syncMatches = true,
        ),
    )

    // Only once the file's observations are gone: a transfer another record still observes is rebuilt from that one, and
    // may then leave an account the file created (its own end, standing in for the other record's placeholder) empty.
    onProgress?.invoke(Progress("Cleaning up empty accounts"))
    val deletedEmptyAccounts =
        importEngine.deleteEmptyImportCreatedAccounts(
            createdAccountIds = plan.createdAccountIds,
            source = source,
            accountRepository = accountRepository,
            tradeRepository = tradeRepository,
            keyPrefix = "unimport-delete",
        )

    if (refreshViews) {
        onProgress?.invoke(Progress("Refreshing views"))
        maintenance.refreshMaterializedViews()
    }

    return CsvUnimportResult(
        deletedTransfers = plan.transferIds.size,
        deletedTrades = plan.tradeIds.size,
        deletedEmptyAccounts = deletedEmptyAccounts,
        unhiddenTransfers = unhideUpdates.size,
        affectedFiles = plan.affectedFiles,
    )
}

/**
 * Preview for unimporting several files at once: the per-file plans, plus the OTHER files (outside the
 * set) whose rows depend on any of them — a file that is itself being unimported needs no warning.
 */
data class CsvBulkUnimportPlan(
    val plans: List<CsvUnimportPlan>,
    val affectedFiles: List<CsvUnimportAffectedFile>,
) {
    val transferCount: Int get() = plans.sumOf { it.transferIds.size }
    val tradeCount: Int get() = plans.sumOf { it.tradeIds.size }
}

suspend fun planCsvBulkUnimport(
    importIds: List<CsvImportId>,
    csvImportRepository: CsvImportReadRepository,
    transferSourceRepository: TransferSourceReadRepository,
): CsvBulkUnimportPlan {
    // Each transaction is counted once, against the first file that withdraws it, and survives only what outlives them all.
    val withdrawing = importIds.toSet()
    val claimed = mutableSetOf<TransactionId>()
    val plans =
        importIds.map { id ->
            planWithdrawal(id, withdrawing, claimed.toSet(), csvImportRepository, transferSourceRepository).also {
                claimed += it.transferIds
                claimed += it.tradeIds
            }
        }
    return CsvBulkUnimportPlan(plans, mergeAffectedFiles(plans.flatMap { it.affectedFiles }, importIds.toSet()))
}

/**
 * Unimports every file in [plan] in turn. Each file is re-planned just before it runs: unimporting an
 * earlier file can reset rows of a later one, so the upfront plans are only a preview. Views are
 * refreshed once at the end rather than per file.
 */
@Suppress("LongParameterList")
suspend fun executeCsvBulkUnimport(
    plan: CsvBulkUnimportPlan,
    csvImportRepository: CsvImportReadRepository,
    transferSourceRepository: TransferSourceReadRepository,
    accountRepository: AccountReadRepository,
    transactionRepository: TransactionReadRepository,
    relationshipRepository: RelationshipReadRepository,
    tradeRepository: TradeReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onProgress: (suspend (Progress) -> Unit)? = null,
): CsvUnimportResult {
    val ids = plan.plans.map { it.importId }
    val results =
        ids.mapIndexed { index, id ->
            val label = "Unimporting file ${index + 1} of ${ids.size}"
            onProgress?.invoke(Progress(label, fraction = index.toFloat() / ids.size, processed = index, total = ids.size))
            executeCsvUnimport(
                plan = planCsvUnimport(id, csvImportRepository, transferSourceRepository),
                accountRepository = accountRepository,
                transactionRepository = transactionRepository,
                relationshipRepository = relationshipRepository,
                tradeRepository = tradeRepository,
                maintenance = maintenance,
                importEngine = importEngine,
                onProgress = { onProgress?.invoke(it.copy(detail = "$label: ${it.detail}")) },
                refreshViews = false,
            )
        }
    onProgress?.invoke(Progress("Refreshing views", fraction = 1f))
    maintenance.refreshMaterializedViews()
    return CsvUnimportResult(
        deletedTransfers = results.sumOf { it.deletedTransfers },
        deletedTrades = results.sumOf { it.deletedTrades },
        deletedEmptyAccounts = results.flatMap { it.deletedEmptyAccounts },
        unhiddenTransfers = results.sumOf { it.unhiddenTransfers },
        affectedFiles = mergeAffectedFiles(results.flatMap { it.affectedFiles }, ids.toSet()),
    )
}

/** One entry per affected file outside [excluding], its row indexes unioned. */
private fun mergeAffectedFiles(
    files: List<CsvUnimportAffectedFile>,
    excluding: Set<CsvImportId>,
): List<CsvUnimportAffectedFile> =
    files
        .filter { it.importId !in excluding }
        .groupBy { it.importId }
        .map { (id, group) ->
            CsvUnimportAffectedFile(id, group.first().fileName, group.flatMap { it.rowIndexes }.distinct().sorted())
        }.sortedBy { it.fileName }
