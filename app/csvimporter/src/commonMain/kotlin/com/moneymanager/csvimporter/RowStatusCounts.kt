package com.moneymanager.csvimporter

import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.applyCsvImportMutations

internal class RowStatusCounts(
    val newTrades: Int,
    val dedupedTrades: Int,
)

/**
 * Writes the engine's outcome back as per-row CSV statuses, clearing the stale error of every row that settled
 * (a row retried from ERROR can resolve as any of them). [assembledTradeRowIndexes] maps an assembled trade to every
 * leg row it speaks for; [reconciledConversionRows] are conversion legs another source already recorded, which read as
 * duplicates rather than as rows that quietly produced nothing.
 */
internal suspend fun writeRowStatuses(
    importEngine: ImportEngine,
    csvImportId: CsvImportId,
    importResult: ImportResult,
    assembledTradeRowIndexes: Map<LocalTradeKey, List<Long>>,
    reconciledConversionRows: Set<Long>,
): RowStatusCounts {
    val importedStatuses = mutableSetOf<Long>()
    val duplicateStatuses = mutableSetOf<Long>()
    val updatedStatuses = mutableSetOf<Long>()
    for ((rowKey, outcome) in importResult.rowOutcomes) {
        val rowIndex = (rowKey as ImportRowKey.CsvRow).rowIndex
        when (outcome.status) {
            ImportStatus.IMPORTED -> importedStatuses += rowIndex
            ImportStatus.DUPLICATE -> duplicateStatuses += rowIndex
            ImportStatus.UPDATED -> updatedStatuses += rowIndex
            ImportStatus.ERROR, ImportStatus.SKIPPED -> Unit
        }
    }
    // Cross-asset conversion rows are imported as trades, which the engine reports via createdTradeIds
    // (keyed by LocalTradeKey), NOT via rowOutcomes. Such a row is IMPORTED, or DUPLICATE when the
    // intent matched an existing identical trade (createTrade is idempotent, e.g. a re-import or the
    // same event arriving from another export); otherwise a converted row keeps a stale ERROR status and
    // gets reprocessed on the next import. The key is "csv-<importId>-<rowIndex>".
    val tradeKeyPrefix = "$CSV_TRADE_KEY_PREFIX${csvImportId.id}-"

    fun LocalTradeKey.rowIndexOrNull(): Long? =
        if (value.startsWith(tradeKeyPrefix)) value.removePrefix(tradeKeyPrefix).toLongOrNull() else null

    // An assembled trade speaks for every leg of its group, not just the row its key names, so all of
    // them take the trade's outcome. A single-row conversion has no group and falls back to its own row.
    fun LocalTradeKey.rowIndexes(): List<Long> = assembledTradeRowIndexes[this] ?: listOfNotNull(rowIndexOrNull())

    val tradeRowsByStatus =
        importResult.createdTradeIds.keys
            .flatMap { key -> key.rowIndexes().map { key to it } }
            .groupBy(
                keySelector = { (key, _) ->
                    if (key in importResult.dedupedTradeKeys) ImportStatus.DUPLICATE else ImportStatus.IMPORTED
                },
                valueTransform = { (_, rowIndex) -> rowIndex },
            )
    duplicateStatuses += reconciledConversionRows

    val statusMutations = mutableListOf<CsvImportMutation>()

    fun settle(
        status: ImportStatus,
        rows: Collection<Long>,
    ) {
        if (rows.isEmpty()) return
        statusMutations += CsvImportMutation.UpdateRowStatuses(csvImportId, status.name, rows)
        statusMutations += CsvImportMutation.ClearErrors(csvImportId, rows.toList())
    }
    settle(ImportStatus.IMPORTED, importedStatuses)
    tradeRowsByStatus.forEach { (status, rows) -> settle(status, rows) }
    settle(ImportStatus.DUPLICATE, duplicateStatuses)
    settle(ImportStatus.UPDATED, updatedStatuses)
    importEngine.applyCsvImportMutations(statusMutations)
    return RowStatusCounts(
        newTrades = tradeRowsByStatus[ImportStatus.IMPORTED]?.size ?: 0,
        dedupedTrades = tradeRowsByStatus[ImportStatus.DUPLICATE]?.size ?: 0,
    )
}
