package com.moneymanager.csvimporter

import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportProgress
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.deleteEmptyImportCreatedAccounts
import com.moneymanager.importengineapi.reconciledPartnerUnhideUpdates
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
    /** Every transfer the file created (fee and pass-through legs included). */
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
 * Works out what unimporting [importId] removes: only what the file itself CREATED (per
 * `entity_source`, first revision) — a transaction another source created and this file merely
 * matched or updated is left alone. Rows in other files that point at a transaction about to be deleted
 * are listed so they can be reset: their movement is gone with it until that file is re-imported.
 */
suspend fun planCsvUnimport(
    importId: CsvImportId,
    csvImportRepository: CsvImportReadRepository,
): CsvUnimportPlan {
    val transferIds = csvImportRepository.getTransferIdsCreatedByImport(importId)
    val tradeIds = csvImportRepository.getTradeIdsCreatedByImport(importId)
    val referencing = csvImportRepository.findRowsReferencingTransactions(importId, transferIds, tradeIds)
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
 * Unimports a file per [plan]: deletes the transactions it created, returns it (and the rows of any
 * [CsvUnimportPlan.affectedFiles]) to the never-imported state, marks it ignored, un-hides transfers
 * from other sources that its transfers had reconciled away, and deletes the accounts it created that
 * are left empty. Its strategy applications are deleted too; the application audit trail keeps the
 * history, which is how the UI knows the file was unimported.
 *
 * Account merges are not reversed: nothing ties a merge to a file, and one the user performed must not
 * be silently undone.
 */
@Suppress("LongParameterList")
suspend fun executeCsvUnimport(
    plan: CsvUnimportPlan,
    accountRepository: AccountReadRepository,
    transactionRepository: TransactionReadRepository,
    transferRelationshipRepository: TransferRelationshipReadRepository,
    tradeRepository: TradeReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
    refreshViews: Boolean = true,
): CsvUnimportResult {
    val source = Source.Csv(plan.importId)
    val unhideUpdates = reconciledPartnerUnhideUpdates(plan.transferIds, transferRelationshipRepository, transactionRepository)

    // Every step before the final file reset can be re-derived from entity_source, so each batch leaves
    // a state a repeated unimport recovers from; the file only reads as unimported once all of them
    // succeeded. The un-hide updates share the delete batch (the engine applies updates before deletes),
    // and so do the other files' row resets, which can't be found again once the transactions are gone.
    onProgress?.invoke(ImportProgress("Removing the file's transactions"))
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

    onProgress?.invoke(ImportProgress("Cleaning up empty accounts"))
    val deletedEmptyAccounts =
        importEngine.deleteEmptyImportCreatedAccounts(
            createdAccountIds = plan.createdAccountIds,
            source = source,
            accountRepository = accountRepository,
            tradeRepository = tradeRepository,
            keyPrefix = "unimport-delete",
        )

    importEngine.import(
        ImportBatch(
            csvImportMutations =
                listOf(
                    CsvImportMutation.ResetToUnimported(plan.importId),
                    CsvImportMutation.SetIgnored(plan.importId, ignored = true),
                ),
        ),
    )

    if (refreshViews) {
        onProgress?.invoke(ImportProgress("Refreshing views"))
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
): CsvBulkUnimportPlan {
    val plans = importIds.map { planCsvUnimport(it, csvImportRepository) }
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
    accountRepository: AccountReadRepository,
    transactionRepository: TransactionReadRepository,
    transferRelationshipRepository: TransferRelationshipReadRepository,
    tradeRepository: TradeReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
): CsvUnimportResult {
    val ids = plan.plans.map { it.importId }
    val results =
        ids.mapIndexed { index, id ->
            val label = "Unimporting file ${index + 1} of ${ids.size}"
            onProgress?.invoke(ImportProgress(label, fraction = index.toFloat() / ids.size, processed = index, total = ids.size))
            executeCsvUnimport(
                plan = planCsvUnimport(id, csvImportRepository),
                accountRepository = accountRepository,
                transactionRepository = transactionRepository,
                transferRelationshipRepository = transferRelationshipRepository,
                tradeRepository = tradeRepository,
                maintenance = maintenance,
                importEngine = importEngine,
                onProgress = { onProgress?.invoke(it.copy(detail = "$label: ${it.detail}")) },
                refreshViews = false,
            )
        }
    onProgress?.invoke(ImportProgress("Refreshing views", fraction = 1f))
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
