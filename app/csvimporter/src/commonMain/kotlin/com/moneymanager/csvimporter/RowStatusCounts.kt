package com.moneymanager.csvimporter

import com.moneymanager.coroutines.runCatchingCancellable
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoRegistry
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.codesByName
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.csvstrategy.ownedAttributeValues
import com.moneymanager.domain.model.csvstrategy.uniqueIdentifierAttributeTypes
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.resolveAssetCode
import com.moneymanager.domain.model.rules.ColumnRecord
import com.moneymanager.domain.model.rules.EXCLUDED_ATTRIBUTE_TYPE_NAME
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleEngine
import com.moneymanager.domain.model.rules.RuleEvaluator
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.assetAliasMap
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.BatchRelationship
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.ExistingUniqueKeyExtractor
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportFee
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPassThrough
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.ImportReportedBalance
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.ObservationOwnerKey
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.importengineapi.PassThroughDetector
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.applyCsvImportMutations
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.createAccountMapping
import com.moneymanager.importengineapi.createAccountMappings
import com.moneymanager.importengineapi.createAccounts
import com.moneymanager.importengineapi.createCrypto
import com.moneymanager.importengineapi.dedupePolicy
import com.moneymanager.importengineapi.deleteEmptyImportCreatedAccounts
import com.moneymanager.importengineapi.feeAccounts
import com.moneymanager.importengineapi.getOrCreateAttributeTypes
import com.moneymanager.importengineapi.normalizeNameKey
import com.moneymanager.importengineapi.tradeDedupePolicy
import com.moneymanager.tasks.Progress
import com.moneymanager.tasks.WeightedProgress
import com.moneymanager.xlsx.createXlsxParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
