package com.moneymanager.csvimporter

import co.touchlab.kermit.Logger
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

private val logger = Logger.withTag("CsvRestaging")

/**
 * [csvImport] staged as [strategy] reads it (see [restageXlsxForStrategy] and [restagePdfForStrategy]): reloaded
 * when its rows had to be staged again, else as it was.
 */
suspend fun restageForStrategy(
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    csvImportRepository: CsvImportReadRepository,
    importEngine: ImportEngine,
): CsvImport =
    restagePdfForStrategy(
        restageXlsxForStrategy(csvImport, strategy, csvImportRepository, importEngine),
        strategy,
        csvImportRepository,
        importEngine,
    )

private suspend fun restagePdfForStrategy(
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    csvImportRepository: CsvImportReadRepository,
    importEngine: ImportEngine,
): CsvImport = planPdfRestage(csvImport, strategy, csvImportRepository)?.apply(importEngine, csvImportRepository) ?: csvImport

/** The rows the strategy assembles from a PDF's staged text, or null when the staging can be used as it is. */
suspend fun planPdfRestage(
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    csvImportRepository: CsvImportReadRepository,
): CsvImportMutation.Restage? {
    val pdf = csvImportRepository.getPdfSource(csvImport.id) ?: return null
    val lineConfig = strategy.config.pdfLines
    val rulesHash = lineConfig?.let { PdfRowAssembler.rulesHash(it) }
    if (pdf.stagedRulesHash == rulesHash) return null
    if (csvImport.lastAppliedAt != null) {
        // Its transactions point at the staged rows by index; a re-import reserves them before replacing the rows.
        logger.w { "PDF import ${csvImport.id.id} was imported with other line rules; keeping its rows until it's re-imported" }
        return null
    }
    val staged = lineConfig?.let { PdfRowAssembler(it).assemble(pdf.lines) } ?: PdfRowAssembler.rawLines(pdf.lines)
    return CsvImportMutation.Restage(csvImport.id, staged.headers, staged.rows, pdfRulesHash = rulesHash)
}

internal suspend fun restageXlsxForStrategy(
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    csvImportRepository: CsvImportReadRepository,
    importEngine: ImportEngine,
): CsvImport = planXlsxRestage(csvImport, strategy, csvImportRepository)?.apply(importEngine, csvImportRepository) ?: csvImport

/** The rows of the strategy's worksheet when it differs from the staged sheet. */
suspend fun planXlsxRestage(
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    csvImportRepository: CsvImportReadRepository,
): CsvImportMutation.Restage? {
    val targetSheet = strategy.worksheetName ?: return null
    val blob = csvImportRepository.getXlsxBlob(csvImport.id) ?: return null
    if (blob.worksheetName == targetSheet) return null
    val parsed = createXlsxParser().parse(blob.fileBytes, targetSheet)
    return CsvImportMutation.Restage(csvImport.id, parsed.headers, parsed.rows, worksheetName = targetSheet)
}

internal suspend fun CsvImportMutation.Restage.apply(
    importEngine: ImportEngine,
    reads: CsvImportReadRepository,
): CsvImport? {
    importEngine.applyCsvImportMutations(listOf(this))
    logger.i { "Re-staged import $id with ${rows.size} rows" }
    return reads.getImport(id).first()
}
