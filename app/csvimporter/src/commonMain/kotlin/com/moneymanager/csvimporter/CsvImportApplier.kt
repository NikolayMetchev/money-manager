package com.moneymanager.csvimporter

import co.touchlab.kermit.Logger
import com.moneymanager.coroutines.runCatchingCancellable
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.csvstrategy.ownedAttributeValues
import com.moneymanager.domain.model.csvstrategy.uniqueIdentifierAttributeTypes
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.passthrough.PassThroughAccount
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
import com.moneymanager.importengineapi.ImportPassThrough
import com.moneymanager.importengineapi.ImportReportedBalance
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.ObservationOwnerKey
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.importengineapi.PassThroughDetector
import com.moneymanager.importengineapi.applyCsvImportMutations
import com.moneymanager.importengineapi.dedupePolicy
import com.moneymanager.importengineapi.deleteEmptyImportCreatedAccounts
import com.moneymanager.importengineapi.feeAccounts
import com.moneymanager.importengineapi.getOrCreateAttributeTypes
import com.moneymanager.importengineapi.tradeDedupePolicy
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private val logger = Logger.withTag("CsvImportApplier")

/**
 * Collapses consecutive duplicate accounts out of a pass-through spend-leg chain ([nodes] = the conduit
 * account ids followed by the merchant account id), keeping [spendDescriptions] aligned 1:1 with the
 * surviving conduit legs by dropping the description of each removed leg.
 *
 * The engine builds one spend leg per adjacent node pair (`nodes[i] -> nodes[i+1]`). A node that equals
 * its predecessor — e.g. a merchant that resolved to its feeding conduit, or a repeated conduit — would
 * make that leg a zero-movement `source == target` transfer, which the `transfer` CHECK constraint
 * rejects (aborting the whole file). Returns the collapsed (nodes, descriptions), or null when fewer
 * than two distinct nodes remain (no real pass-through — the caller imports the plain funding transfer).
 */
internal fun collapsePassThroughChain(
    nodes: List<AccountId>,
    spendDescriptions: List<String>,
): Pair<List<AccountId>, List<String>>? {
    if (nodes.isEmpty()) return null
    val keptNodes = mutableListOf(nodes.first())
    val keptDescriptions = mutableListOf<String>()
    for (legIndex in 0 until nodes.size - 1) {
        if (nodes[legIndex + 1] != keptNodes.last()) {
            keptNodes += nodes[legIndex + 1]
            keptDescriptions += spendDescriptions.getOrElse(legIndex) { "" }
        }
    }
    return if (keptNodes.size < 2) null else keptNodes to keptDescriptions
}

/**
 * The row's own account: a fee comes out of it, so a fee on money coming in isn't charged to whoever sent it, and a
 * balance the row reports is that account's.
 */
private fun MappingResult.Success.feePayerId(): AccountId =
    statementAccountId?.takeIf { it != UNRESOLVED_ACCOUNT_ID } ?: transfer.sourceAccountId

/** The account whose fee account the row names on its other side ([OWN_FEE_ACCOUNT_ID]), or null. */
private fun MappingResult.Success.ownerOfFeeAccountSide(): AccountId? =
    when (OWN_FEE_ACCOUNT_ID) {
        transfer.targetAccountId -> transfer.sourceAccountId
        transfer.sourceAccountId -> transfer.targetAccountId
        else -> null
    }

/** The row with [OWN_FEE_ACCOUNT_ID] replaced by its owner's fee account from [feeAccountOf]. */
private fun MappingResult.Success.withOwnFeeAccount(feeAccountOf: Map<AccountId, AccountId>): MappingResult.Success {
    val owner = ownerOfFeeAccountSide() ?: return this
    val feeAccount = checkNotNull(feeAccountOf[owner]) { "No fee account for $owner" }

    fun AccountId.resolved() = if (this == OWN_FEE_ACCOUNT_ID) feeAccount else this
    return copy(
        transfer =
            transfer.copy(
                sourceAccountId = transfer.sourceAccountId.resolved(),
                targetAccountId = transfer.targetAccountId.resolved(),
            ),
    )
}

/**
 * Applies a fixed [strategy] to all importable rows of an already-staged [csvImport] and returns the
 * run result (null when there are no rows left to import). Unlike [bulkApplyCsv] the strategy is given,
 * not auto-matched — used by re-import and by [bulkApplyCsv] itself after it selects one.
 * [refreshViews] = false lets a caller batch many files and refresh once at the end.
 * [onProgress]/[engineBatchSize] are forwarded to the engine so a caller can show per-chunk progress.
 * [syncMatches] = false leaves re-matching the stored observations ([ImportBatch.syncMatches]) to the caller, which
 * runs it once after many files.
 */
@Suppress("LongParameterList")
suspend fun applyStagedCsv(
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    sourceAccountOverride: AccountId?,
    currencies: List<Currency>,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    csvImportRepository: CsvImportReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    refreshViews: Boolean,
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    onProgress: (suspend (Progress) -> Unit)? = null,
    engineBatchSize: Int = Int.MAX_VALUE,
    cryptoRepository: CryptoReadRepository? = null,
    attributeAccountMatchers: Map<String, AttributeAccountMatcher> = emptyMap(),
    tradeRepository: TradeReadRepository? = null,
    syncMatches: Boolean = true,
    // Raw codes from the batch preflight for this file and strategy.
    preparedAssetCodes: Set<String>? = null,
): CsvImportResult? {
    // The staged rows must be the ones the strategy reads: an Excel import is initially staged from the workbook's
    // FIRST worksheet, but the strategy may target a different sheet, and a PDF import's rows are whatever its
    // strategy's line rules assemble. Re-stage before reading rows so everything downstream sees those.
    require(strategy.supportsFile(csvImport.originalFileName)) { "The strategy does not read this file format" }
    val stagedImport = restageForStrategy(csvImport, strategy, csvImportRepository, importEngine)

    onProgress?.invoke(Progress("Loading rows"))
    val allRows = csvImportRepository.getImportRows(stagedImport.id, limit = stagedImport.rowCount.coerceAtLeast(1), offset = 0)
    val unprocessedRows = allRows.filter { it.importStatus == null || it.importStatus == ImportStatus.ERROR }
    // A strategy that assembles a trade from a whole row group must see the group whole: mapping only
    // the unprocessed rows would turn a group with one errored leg into a one-legged group and assemble
    // it wrongly (or not at all). Re-mapping the settled legs costs a pass and changes nothing — the
    // trade's exact-tuple idempotency makes re-emitting the group a no-op that resolves to DUPLICATE.
    // Transfers are still emitted only for the unprocessed rows (see [unprocessedRowIndexes] below), so
    // widening the mapped set changes what groups see and nothing else.
    val rows = if (strategy.config.legGroups.any { it.assembly is LegAssembly.Trade }) allRows else unprocessedRows
    val unprocessedRowIndexes: Set<Long> = unprocessedRows.mapTo(mutableSetOf()) { it.rowIndex }
    if (unprocessedRows.isEmpty()) {
        // A genuinely empty file (a header-only export with no data rows) has nothing to import, but
        // must still be marked applied or it reappears in the Unimported tab on every "Import all".
        // Record the strategy application once (the lastAppliedAt guard keeps repeated runs a no-op).
        // Scoped to allRows.isEmpty() so it never fires on re-import, which always has rows and whose
        // "nothing new to import" outcome must stay a null result.
        if (allRows.isEmpty() && stagedImport.lastAppliedAt == null) {
            recordCsvApplication(importEngine, stagedImport.id, strategy, sourceAccountOverride)
            return CsvImportResult(successCount = 0, failedRows = emptyList())
        }
        return null
    }

    // The shared override only applies to strategies that need a user-chosen source. A hard-coded
    // mapping resolves its own account; a per-row mapping decides per row.
    val effectiveSource = effectiveSourceFor(strategy, sourceAccountOverride)

    // On-demand: create a crypto asset for every non-fiat ticker appearing in the strategy's currency
    // column, so rows denominated in crypto resolve to a real crypto asset (upsert = idempotent on
    // re-import). No-op unless a CryptoReadRepository is supplied and the currency column carries crypto.
    onProgress?.invoke(Progress("Mapping rows"))
    val cryptoAssets =
        ensureScannedCryptoAssets(strategy, importEngine, cryptoRepository, currencies) {
            preparedAssetCodes?.takeIf { stagedImport === csvImport && rows.size == allRows.size }
                ?: collectAssetCodes(strategy, stagedImport.columns, stagedImport.originalFileName) { rows }
        }
    val assetAliases = cryptoRepository?.assetAliasMap().orEmpty()

    // Re-fetch accounts so payee accounts created by earlier files in the same scan are seen.
    val accounts = accountRepository.accountsVisibleTo(strategy)
    val mappings = accountMappingRepository.getAllMappings().first()
    val historicalAccountNames = accountRepository.getPreviousAccountNames()
    val basePrep =
        buildCsvMapper(
            strategy,
            stagedImport.columns,
            accounts,
            currencies,
            mappings,
            effectiveSource,
            passThroughAccounts,
            historicalAccountNames,
            cryptoAssets,
            attributeAccountMatchers,
            assetAliases,
            stagedImport.originalFileName,
            accountRepository.getAccountOwnerNames(),
        ).prepareImport(rows)

    val result =
        runCsvImport(
            cryptoAssets = cryptoAssets,
            assetAliases = assetAliases,
            csvImport = stagedImport,
            rows = rows,
            columns = stagedImport.columns,
            strategy = strategy,
            basePrep = basePrep,
            selectedExistingAccounts = emptyMap(),
            selectedNewAccountNames = emptyMap(),
            selectedSourceAccountId = effectiveSource,
            currencies = currencies,
            accountMappingRepository = accountMappingRepository,
            accountRepository = accountRepository,
            maintenance = maintenance,
            importEngine = importEngine,
            refreshViews = refreshViews,
            passThroughAccounts = passThroughAccounts,
            onProgress = onProgress,
            engineBatchSize = engineBatchSize,
            attributeAccountMatchers = attributeAccountMatchers,
            unprocessedRowIndexes = unprocessedRowIndexes,
            tradeRepository = tradeRepository,
            visibleAccounts = accounts,
            foreignFiles = crossFileLegRows(stagedImport, strategy, csvImportRepository),
        )
    // Read after the run, which may have saved mappings: a re-import compares against what this run leaves.
    val inputsHash =
        stagedInputsHash(
            strategy = strategy,
            sourceAccount = effectiveSource,
            mappings = accountMappingRepository.getAllMappings().first(),
            assetAliases = cryptoRepository?.assetAliasMap().orEmpty(),
            attributeAccountMatchers = attributeAccountMatchers,
            passThroughAccounts = passThroughAccounts,
            accountOwnerNames = accountRepository.getAccountOwnerNames(),
        )
    importEngine.applyCsvImportMutations(listOf(recordRowsMutation(stagedImport, allRows, inputsHash)))
    // An account the file's rules named that nothing ended up on (a conversion counterparty whose legs paired into a
    // trade) goes, as a re-run of the file removes it: a fresh import and a re-import then hold the same accounts.
    importEngine.deleteEmptyImportCreatedAccounts(
        createdAccountIds = csvImportRepository.getAccountsCreatedByImport(stagedImport.id),
        source = Source.Csv(stagedImport.id),
        accountRepository = accountRepository,
        tradeRepository = tradeRepository,
    )
    val created =
        csvImportRepository
            .getImportRows(stagedImport.id, limit = stagedImport.rowCount.coerceAtLeast(1), offset = 0)
            .filter { it.rowIndex in unprocessedRowIndexes && it.importStatus == ImportStatus.IMPORTED }
            .mapNotNullTo(mutableSetOf()) { it.transferId }
    val booked = result.copy(createdTransferIds = created)
    if (!syncMatches) return booked
    onProgress?.invoke(Progress("Matching observations"))
    return booked.foldedInto(importEngine.import(ImportBatch(syncMatches = true)).matchSync.deleted)
}

/**
 * Records that [strategy] was applied to [csvImportId], moving the file into the "Imported" tab
 * (`last_applied_at` becomes non-null), with the source account and currency the run used, so a
 * re-import runs with them again rather than asking. Failures are logged, not thrown — the transfers
 * are already committed, so a missing history row must not fail the import.
 */
internal suspend fun recordCsvApplication(
    importEngine: ImportEngine,
    csvImportId: CsvImportId,
    strategy: CsvImportStrategy,
    sourceAccountOverride: AccountId?,
) {
    runCatchingCancellable {
        importEngine.applyCsvImportMutations(
            listOf(
                CsvImportMutation.RecordApplication(
                    id = csvImportId,
                    strategyId = strategy.id,
                    strategyName = strategy.name,
                    appliedAt = Clock.System.now(),
                    accountId = effectiveSourceFor(strategy, sourceAccountOverride),
                    currencyId = (strategy.config.fieldMappings[TransferField.CURRENCY] as? HardCodedCurrencyMapping)?.currencyId,
                ),
            ),
        )
    }.onFailure { error ->
        logger.w { "Import application history could not be recorded for import $csvImportId: ${error.message}" }
    }
}

/**
 * Resolves the source account to use for a file under [strategy]:
 * - a [HardCodedAccountMapping] resolves its own account (the override is ignored),
 * - a per-row SOURCE_ACCOUNT mapping decides per row (null override),
 * - no SOURCE_ACCOUNT mapping falls back to the shared [override].
 */
fun effectiveSourceFor(
    strategy: CsvImportStrategy,
    override: AccountId?,
): AccountId? =
    when (val mapping = strategy.config.fieldMappings[TransferField.SOURCE_ACCOUNT]) {
        is HardCodedAccountMapping -> mapping.accountId
        null -> override
        else -> null
    }

/** True when `strategy` needs a user-chosen source account (no SOURCE_ACCOUNT mapping of its own). */
fun CsvImportStrategy.needsSourceAccountOverride(): Boolean = config.fieldMappings[TransferField.SOURCE_ACCOUNT] == null

fun buildCsvMapper(
    strategy: CsvImportStrategy,
    columns: List<CsvColumn>,
    accounts: List<Account>,
    currencies: List<Currency>,
    accountMappings: List<AccountMapping>,
    sourceAccountOverride: AccountId?,
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    historicalAccountNames: Map<String, AccountId> = emptyMap(),
    cryptoAssets: List<CryptoAsset> = emptyList(),
    attributeAccountMatchers: Map<String, AttributeAccountMatcher> = emptyMap(),
    assetAliases: Map<String, String> = emptyMap(),
    fileName: String? = null,
    accountOwnerNames: Map<AccountId, List<String>> = emptyMap(),
): TransferMapper =
    TransferMapper(
        strategy = strategy,
        columns = columns,
        existingAccounts = accounts.associateBy { it.name },
        existingCurrencies = currencies.associateBy { it.id },
        existingCurrenciesByCode = currencies.associateBy { it.code.uppercase() },
        existingCryptoByCode = cryptoAssets.associateBy { it.code.uppercase() },
        assetAliases = assetAliases,
        accountMappings = accountMappings,
        historicalAccountNames = historicalAccountNames,
        sourceAccountOverride = sourceAccountOverride,
        passThroughDetector = passThroughAccounts.takeIf { it.isNotEmpty() }?.let { PassThroughDetector(it) },
        attributeAccountMatchers = attributeAccountMatchers,
        fileName = fileName,
        accountOwnerNames = accountOwnerNames,
    )

private fun ImportPreparation.skippedRowMutations(id: CsvImportId): List<CsvImportMutation> =
    if (skippedRowIndexes.isEmpty()) {
        emptyList()
    } else {
        listOf(
            CsvImportMutation.UpdateRowStatuses(id, ImportStatus.SKIPPED.name, skippedRowIndexes),
            CsvImportMutation.ClearErrors(id, skippedRowIndexes),
        )
    }

/**
 * Applies [strategy] to [rows] of a single [csvImport] and writes back per-row statuses. Creates new
 * accounts the user accepted, persists/auto-captures account mappings, runs the central import engine,
 * and records the strategy application. Shared by the single-file dialog and the bulk path; the bulk
 * path passes [refreshViews] = false and refreshes once at the end.
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
suspend fun runCsvImport(
    csvImport: CsvImport,
    rows: List<CsvRow>,
    columns: List<CsvColumn>,
    strategy: CsvImportStrategy,
    basePrep: ImportPreparation,
    selectedExistingAccounts: Map<String, AccountId>,
    selectedNewAccountNames: Map<String, String>,
    selectedSourceAccountId: AccountId?,
    currencies: List<Currency>,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    refreshViews: Boolean = true,
    cryptoAssets: List<CryptoAsset> = emptyList(),
    assetAliases: Map<String, String> = emptyMap(),
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    onProgress: (suspend (Progress) -> Unit)? = null,
    engineBatchSize: Int = Int.MAX_VALUE,
    attributeAccountMatchers: Map<String, AttributeAccountMatcher> = emptyMap(),
    // Rows still awaiting import. Equals every row index in [rows] except when the strategy assembles
    // trades from row groups, where [rows] is widened to the whole file so a group is never seen with
    // some of its legs missing; transfers are then still emitted only for the rows named here.
    unprocessedRowIndexes: Set<Long> = rows.mapTo(mutableSetOf()) { it.rowIndex },
    tradeRepository: TradeReadRepository? = null,
    foreignFiles: List<ForeignFile> = emptyList(),
    // Supplies the account snapshot used by a freshly computed basePrep for these exact rows and settings.
    visibleAccounts: List<Account>? = null,
): CsvImportResult {
    logger.i { "Starting CSV import with ${basePrep.validTransfers.size} valid transfers" }

    // A reconciliation source may only be pointed at its own shadow accounts; drop any "map to existing
    // account" choice that names a real one (the row then creates its shadow account as usual).
    val knownAccounts = visibleAccounts ?: accountRepository.accountsVisibleTo(strategy)
    val visibleAccountsById = knownAccounts.associateBy { it.id }
    val existingSelections = selectedExistingAccounts.filterValues { it in visibleAccountsById }
    val accountsToCreate =
        buildAccountsToCreate(
            preparation = basePrep,
            existingAccountSelections = existingSelections,
            newAccountNames = selectedNewAccountNames,
        )
    val selectedMappingsToPersist =
        buildPendingAccountMappings(
            preparation = basePrep,
            accountSelections = existingSelections,
            accountsById = visibleAccountsById,
        )
    persistMappingsWithFallback(importEngine, selectedMappingsToPersist)

    // Create new accounts first (skip failures - transfers using them will fail later).
    // Record CSV provenance pointing each account at the first row that referenced it.
    val firstRowByAccountName = buildFirstRowByAccountName(basePrep, selectedNewAccountNames)
    createNewAccounts(
        importEngine = importEngine,
        accountsToCreate = accountsToCreate,
        csvImportId = csvImport.id,
        firstRowByAccountName = firstRowByAccountName,
        shadowSource = strategy.shadowSource,
    )

    // No auto-capture of template/regex/exact mappings: the strategy re-derives the target account
    // name on every import, and a later rename is resolved via audit history (getPreviousAccountNames),
    // so these would be redundant (and, stored globally, would leak across strategies). Only explicit
    // "map to existing account" selections are persisted (above), since those are not re-derivable.

    // Handle case when all rows are already processed (rows is already filtered by the caller)
    if (rows.isEmpty()) {
        logger.i { "No rows to process - all rows already imported" }
        return CsvImportResult(successCount = 0, failedRows = emptyList())
    }

    // An interactive preview may use different settings from the final import. The unattended path just
    // prepared these rows with the final settings, so it only needs to remap when account resolution changed.
    val reusePreparation = visibleAccounts != null && accountsToCreate.isEmpty() && selectedMappingsToPersist.isEmpty()
    val updatedAccounts = if (accountsToCreate.isEmpty()) knownAccounts else accountRepository.accountsVisibleTo(strategy)
    val accountsByName = updatedAccounts.associateBy { it.name }
    val mapperFor: ((String) -> TransferMapper)? =
        if (reusePreparation && foreignFiles.isEmpty()) {
            null
        } else {
            val latestAccountMappings = accountMappingRepository.getAllMappings().first()
            val historicalAccountNames = accountRepository.getPreviousAccountNames()
            val accountOwnerNames = accountRepository.getAccountOwnerNames()
            // Rules see the name of the file each row came from, including rows from other staged files.
            val forFile: (String) -> TransferMapper = { fileName ->
                buildCsvMapper(
                    strategy,
                    columns,
                    updatedAccounts,
                    currencies,
                    latestAccountMappings,
                    selectedSourceAccountId,
                    passThroughAccounts,
                    historicalAccountNames,
                    cryptoAssets,
                    attributeAccountMatchers,
                    assetAliases,
                    fileName,
                    accountOwnerNames,
                )
            }
            forFile
        }
    val preparation = if (reusePreparation) basePrep else checkNotNull(mapperFor)(csvImport.originalFileName).prepareImport(rows)
    val mappedPrep = preparation.withUnresolvedAccountsAsErrors()
    // Each account a row charges a fee to pays it into its own fee account (per-row fees are their own movements, linked to
    // the main transfer), and so does a row that is itself a fee (its `Fee` is all of its amount). The engine resolves them.
    val feeAccountOf: Map<AccountId, AccountId> =
        importEngine.feeAccounts(
            mappedPrep.validTransfers.flatMapTo(mutableSetOf()) { row ->
                listOfNotNull(row.feePayerId().takeIf { row.feeAmount != null }, row.ownerOfFeeAccountSide())
            },
            Source.Csv(csvImport.id),
            shadowSource = strategy.shadowSource,
        )
    val finalPrep = mappedPrep.copy(validTransfers = mappedPrep.validTransfers.map { it.withOwnFeeAccount(feeAccountOf) })
    // The other staged files' legs of a cross-file trade group, mapped with the accounts this file just resolved.
    val foreignLegs: List<MappingResult.Success> =
        if (foreignFiles.isEmpty()) {
            emptyList()
        } else {
            val foreignMapper = checkNotNull(mapperFor)
            val crossFileRules =
                strategy.config.legGroups
                    .withIndex()
                    .filter { (_, rule) ->
                        rule.isCrossFileTrade()
                    }.map { it.index }
                    .toSet()
            foreignFiles
                .flatMap { file ->
                    foreignMapper(file.fileName).prepareImport(file.rows).validTransfers.filter { it.groupLeg?.ruleIndex in crossFileRules }
                }.distinctForeignLegs(finalPrep.validTransfers, strategy.config.uniqueIdentifierAttributeTypes().toSet())
        }
    val validCount = finalPrep.validTransfers.size
    val errorCount = finalPrep.errorRows.size
    logger.i { "Prepared $validCount valid transfers, $errorCount error rows" }

    // Mark mapping errors as ERROR status in database and save error messages. Skipped rows are settled
    // too, so they never count as pending again.
    importEngine.applyCsvImportMutations(
        listOf(
            CsvImportMutation.UpdateRowStatuses(
                csvImport.id,
                ImportStatus.ERROR.name,
                finalPrep.errorRows.map { it.rowIndex },
            ),
            CsvImportMutation.SaveErrors(csvImport.id, finalPrep.errorRows.associate { it.rowIndex to it.errorMessage }),
        ) + finalPrep.skippedRowMutations(csvImport.id),
    )

    // Pre-resolve attribute types. The unidentified-counterparty marker is engine-facing rather than a
    // mapped column, and is resolved for every import: even a file that identifies both ends of every row
    // needs it, to recognise (and supersede) the placeholder legs an earlier export left behind.
    // Every mapped type is resolved, not only those some row carries: the strategy owns them all, and a
    // re-import must be able to remove one a row no longer reports (e.g. `excluded` on an un-deleted row).
    val ownedAttributeValues = strategy.config.ownedAttributeValues()
    val allAttributeTypeNames =
        finalPrep.validTransfers
            .flatMap { it.attributes }
            .map { it.first }
            .toSet() + ownedAttributeValues.keys + WellKnownIds.UNIDENTIFIED_COUNTERPARTY_ATTR_TYPE_NAME
    val attributeTypeIdByName = importEngine.getOrCreateAttributeTypes(allAttributeTypeNames.toList())
    val unidentifiedCounterpartyTypeId = attributeTypeIdByName[WellKnownIds.UNIDENTIFIED_COUNTERPARTY_ATTR_TYPE_NAME]
    // The values each owned type can take (see ownedAttributeValues): a re-import only removes an owned
    // value it could have written.
    val ownedAttributes: Map<AttributeTypeId, Set<String>?> =
        ownedAttributeValues
            .mapNotNull { (name, values) -> attributeTypeIdByName[name]?.let { it to values } }
            .toMap()

    logger.i { "Starting to import $validCount transfers" }

    // Convert attributes from (typeName, value) to NewAttribute
    fun attributesFor(attributes: List<Pair<String, String>>): List<NewAttribute> =
        attributes.mapNotNull { (typeName, value) ->
            val typeId = attributeTypeIdByName[typeName]
            if (typeId != null) NewAttribute(typeId, value) else null
        }

    // Build the unified import batch. Accounts were already resolved/created
    // above, so transfers carry Existing refs and the central engine only
    // dedupes, writes transfers, applies updates and records sources.
    val uniqueIdTypeNames = strategy.config.uniqueIdentifierAttributeTypes().toSet()

    // Sources that split one trade across several rows (Binance stamps every partial fill of both legs
    // with the same second) are assembled here: each resolvable group folds into one trade and its legs
    // drop out of the transfer list. A group that does not resolve assembles to null and its rows stay
    // ordinary transfers, so nothing is ever dropped for want of a clean pairing.
    val assembledTrades = assembleCsvTradeGroups(strategy, finalPrep.validTransfers + foreignLegs)
    val assembledRowIndexes: Set<Long> = assembledTrades.flatMapTo(mutableSetOf()) { it.group.rowIndexes }
    // A leg whose partner is in a file not staged yet waits: booking it alone would count the exchange twice once
    // the partner arrives and the trade is booked.
    val crossFileRuleIndexes =
        strategy.config.legGroups
            .withIndex()
            .filter { (_, rule) -> rule.isCrossFileTrade() }
            .mapTo(mutableSetOf()) { it.index }
    val unpairedLegs =
        finalPrep.validTransfers.filter {
            it.groupLeg?.ruleIndex in crossFileRuleIndexes &&
                it.rowIndex !in assembledRowIndexes
        }
    // A rule that names an asset books its unpaired leg against it (see below); any other leg waits.
    val placeholderLegs = unpairedLegs.filter { strategy.unpairedAssetOf(it) != null && it.rowIndex in unprocessedRowIndexes }
    val heldBackRowIndexes: Set<Long> = unpairedLegs.mapTo(mutableSetOf()) { it.rowIndex }
    val manualEntryTypeId =
        placeholderLegs
            .takeIf { it.isNotEmpty() }
            ?.let {
                importEngine.getOrCreateAttributeTypes(
                    listOf(WellKnownIds.MANUAL_ENTRY_ATTR_TYPE_NAME),
                )
            }?.get(WellKnownIds.MANUAL_ENTRY_ATTR_TYPE_NAME)
    val placeholderTrades =
        manualEntryTypeId?.let { unpairedLegTrades(placeholderLegs, strategy, csvImport.id, importEngine, it) }.orEmpty()
    val supersedingExclusions =
        supersededPlaceholders(assembledTrades, strategy, tradeRepository, AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID))

    // Conversion groups another source already recorded as trades (Binance's dust API reports the same
    // sweep the export splits into unpairable legs). They are matched and dropped as whole groups: see
    // ConversionGroupReconciler for why only the debited legs can be compared, and why a partial match
    // must not suppress anything.
    val reconciledConversionRows: Map<Long, TradeId> =
        reconcileConversionGroups(strategy, finalPrep.validTransfers, tradeRepository)
    val assembledTradeRowIndexes: Map<LocalTradeKey, List<Long>> =
        assembledTrades.associate { assembled ->
            assembled.tradeKey(csvImport.id) to assembled.group.ownRowIndexes
        }

    // Rows carrying a credited leg (Currency != To Currency) are cross-asset conversions → trades.
    val importTrades =
        finalPrep.validTransfers.mapNotNull { row ->
            val credit = row.tradeTo ?: return@mapNotNull null
            ImportTradeIntent(
                key = LocalTradeKey("$CSV_TRADE_KEY_PREFIX${csvImport.id.id}-${row.rowIndex}"),
                source = Source.Csv(csvImport.id),
                timestamp = row.transfer.timestamp,
                description = row.transfer.description,
                fromAccountId = row.transfer.sourceAccountId,
                fromAmount = row.transfer.amount,
                toAccountId = row.transfer.targetAccountId,
                toAmount = credit,
                attributes = attributesFor(row.attributes),
                ownedAttributes = ownedAttributes,
                owner = ObservationOwnerKey.Record(ImportRecordKey.CsvRow(csvImport.id, row.rowIndex)),
            )
        } +
            assembledTrades.map { assembled ->
                ImportTradeIntent(
                    key = assembled.tradeKey(csvImport.id),
                    source = Source.Csv(csvImport.id),
                    timestamp = assembled.timestamp,
                    description = assembled.description,
                    fromAccountId = assembled.ownerAccountId,
                    fromAmount = assembled.fromAmount,
                    toAccountId = assembled.ownerAccountId,
                    toAmount = assembled.toAmount,
                    owner = ObservationOwnerKey.Group(assembled.groupKey(strategy), assembled.memberKeys(csvImport.id, foreignFiles)),
                )
            } + placeholderTrades
    // A leg of a trade this file books (assembled, or a placeholder) is part of it, but the trade carries no fee, so its fee goes
    // the same way. Only this file's own legs: the other files' are theirs.
    val tradeLegRows: Set<Long> =
        (assembledRowIndexes + placeholderLegs.map { it.rowIndex }).filterTo(mutableSetOf()) { isOwnRow(it) && it in unprocessedRowIndexes }

    // A trade carries no fee field, so a conversion row that also has a fee would otherwise drop it.
    // Emit each such fee as its own standalone movement (own account -> its fee account) so the
    // money isn't lost (e.g. Koinly trades). It carries the row's unique-id attributes, or re-importing
    // an overlapping export would book the fee again: the trade itself dedupes on its tuple, but a
    // key-less transfer never dedupes under a unique-id policy.
    val tradeFeeTransfers =
        finalPrep.validTransfers.mapNotNull { row ->
            if (row.tradeTo == null && row.rowIndex !in tradeLegRows) return@mapNotNull null
            val fee = row.feeAmount ?: return@mapNotNull null
            val payer = row.feePayerId()
            val uniqueAttributes = row.attributes.filter { (name, _) -> name in uniqueIdTypeNames }
            // Excluded with its trade (e.g. a row the source marks deleted).
            val exclusion = attributesFor(row.attributes).filter { it.typeId.id == WellKnownIds.EXCLUDED_ATTR_TYPE_ID }
            ImportTransfer(
                rowKey = ImportRowKey.CsvRow(row.rowIndex),
                fromAccount = AccountRef.Existing(payer),
                toAccount = AccountRef.Existing(feeAccountOf.getValue(payer)),
                source = Source.Csv(csvImport.id),
                timestamp = row.transfer.timestamp,
                description = row.feeDescription ?: "${row.transfer.description} (fee)",
                amount = fee,
                attributes = attributesFor(uniqueAttributes) + exclusion,
                ownedAttributes = ownedAttributes.filterKeys { it.id == WellKnownIds.EXCLUDED_ATTR_TYPE_ID },
                uniqueKey = uniqueAttributes.takeIf { uniqueIdTypeNames.isNotEmpty() }?.toMap(),
                observationSlot = ObservationSlot.FEE,
            )
        }

    // Leg-group linking for `ThroughAccount` rules. Without `fundingWhen`, pair each debit leg to a credit
    // leg (same pairing key, nearest timestamp within the configured window) — both 1:1 swaps and
    // N-debits -> 1-credit dust events (many debits share the one credit). With it, each funding leg pairs
    // one to one with the nearest non-funding leg on the other side and is the link's first transfer.
    // Legs with no in-window partner are left unlinked (still valid, balance-correct).
    val conversionLinkByRow: Map<Long, BatchRelationship> =
        strategy.config.legGroups
            .withIndex()
            .flatMap { (index, rule) ->
                val legs = finalPrep.validTransfers.filter { it.groupLeg?.ruleIndex == index }
                when (val assembly = rule.assembly) {
                    is LegAssembly.Trade -> {
                        emptyList()
                    }

                    is LegAssembly.Linked -> {
                        linkMembersToAnchors(legs, assembly.relationshipTypeName)
                    }

                    is LegAssembly.ThroughAccount -> {
                        if (assembly.fundingWhen.isEmpty()) {
                            pairDebitsToCredits(legs, rule.windowSeconds.seconds, assembly.relationshipTypeName)
                        } else {
                            pairFundingLegs(legs, rule.windowSeconds.seconds, assembly.relationshipTypeName)
                        }
                    }
                }
            }.toMap()

    val importTransfers =
        finalPrep.validTransfers
            .filter {
                it.tradeTo == null &&
                    it.rowIndex !in assembledRowIndexes &&
                    it.rowIndex !in reconciledConversionRows &&
                    it.rowIndex !in heldBackRowIndexes &&
                    it.rowIndex in unprocessedRowIndexes
            }.map { row ->
                val uniqueKey =
                    if (uniqueIdTypeNames.isEmpty()) {
                        null
                    } else {
                        row.attributes
                            .filter { (name, _) -> name in uniqueIdTypeNames }
                            .associate { (name, value) -> name to value }
                    }
                val fee =
                    row.feeAmount?.let { feeMoney ->
                        ImportFee(
                            source = AccountRef.Existing(row.feePayerId()),
                            target = AccountRef.Existing(feeAccountOf.getValue(row.feePayerId())),
                            amount = feeMoney,
                            description = row.feeDescription ?: "Fee",
                            relationshipTypeId = RelationshipTypeId(WellKnownIds.FEE_RELATIONSHIP_TYPE_ID),
                        )
                    }
                // Pass-through (conduit) row: the mapper already routed the transfer's conduit side — the
                // target for an outgoing charge, the source for an incoming refund/cancellation; that side
                // is the chain's first conduit. Resolve the remaining chain conduits + the merchant account
                // the mapper created and let the engine add the spend legs (C1 -> C2, …, Cn -> merchant, or
                // reversed when incoming). accountsByName carries the created conduit + merchant ids.
                val passThrough =
                    row.passThrough?.let { pt ->
                        val merchantId = pt.merchantAccountId ?: accountsByName[pt.merchantName]?.id
                        val firstConduitId = if (pt.incoming) row.transfer.sourceAccountId else row.transfer.targetAccountId
                        val innerConduitIds = pt.conduitNames.drop(1).map { accountsByName[it]?.id }
                        if (merchantId == null || innerConduitIds.any { it == null }) {
                            null
                        } else {
                            val nodes = listOf(firstConduitId) + innerConduitIds.filterNotNull() + merchantId
                            collapsePassThroughChain(nodes, pt.spendDescriptions)?.let { (keptNodes, keptDescriptions) ->
                                ImportPassThrough(
                                    conduits = keptNodes.dropLast(1).map { AccountRef.Existing(it) },
                                    merchantTarget = AccountRef.Existing(keptNodes.last()),
                                    amount = row.transfer.amount,
                                    spendDescriptions = keptDescriptions,
                                    relationshipTypeId = RelationshipTypeId(pt.relationshipTypeId),
                                    incoming = pt.incoming,
                                )
                            }
                        }
                    }
                // Funding reconcile hint: match the row's funding value against the strategy's funding
                // attribute type to find the account that must hold the matching funding leg (e.g. Curve's
                // "7721" -> the Crypto.com Card account, via the `card-last4` attribute regexes). Never point
                // at the row's own source conduit (a self-reconcile makes no sense).
                val fundingMatcher = strategy.config.fundingAttributeMatch?.let { attributeAccountMatchers[it.attributeTypeName] }
                val fundingAccountId =
                    row.fundingMatchValue
                        ?.let { fundingMatcher?.match(it) }
                        ?.takeIf { it != row.transfer.sourceAccountId }
                ImportTransfer(
                    rowKey = ImportRowKey.CsvRow(row.rowIndex),
                    fromAccount = AccountRef.Existing(row.transfer.sourceAccountId),
                    toAccount = AccountRef.Existing(row.transfer.targetAccountId),
                    source = Source.Csv(csvImport.id),
                    statementAccount = row.statementAccountId?.takeIf { it != UNRESOLVED_ACCOUNT_ID }?.let { AccountRef.Existing(it) },
                    timestamp = row.transfer.timestamp,
                    description = row.transfer.description,
                    amount = row.transfer.amount,
                    attributes =
                        attributesFor(row.attributes) +
                            listOfNotNull(
                                // Preserve the stand-in's end so the matcher and materializer can replace it
                                // with the named end of another observation.
                                unidentifiedCounterpartyTypeId
                                    ?.takeIf { row.unidentifiedCounterpartyAccountId != null }
                                    ?.let {
                                        NewAttribute(
                                            it,
                                            if (row.unidentifiedCounterpartyAccountId == row.transfer.sourceAccountId) {
                                                WellKnownIds.UNIDENTIFIED_SOURCE
                                            } else {
                                                WellKnownIds.UNIDENTIFIED_TARGET
                                            },
                                        )
                                    },
                            ),
                    uniqueKey = uniqueKey,
                    ownedAttributes = ownedAttributes,
                    fee = fee,
                    passThrough = passThrough,
                    conversion = row.conversion,
                    batchRelationships = listOfNotNull(conversionLinkByRow[row.rowIndex]),
                    reconcileFundingAccountId = fundingAccountId,
                    unidentifiedCounterpartyAccountId = row.unidentifiedCounterpartyAccountId,
                )
            }

    val persons = personIntentsFor(finalPrep.validTransfers, selectedSourceAccountId, accountsByName, csvImport.id)

    val batch =
        ImportBatch(
            transfers = importTransfers + tradeFeeTransfers,
            shadowSource = strategy.shadowSource,
            peopleToCreate = persons.people,
            ownerships = persons.ownerships,
            trades = importTrades,
            reportedBalances =
                finalPrep.validTransfers.mapNotNull { row ->
                    row.reportedBalance?.let {
                        ImportReportedBalance(
                            record = ImportRecordKey.CsvRow(csvImport.id, row.rowIndex),
                            account = AccountRef.Existing(row.feePayerId()),
                            timestamp = row.reportedBalanceAt ?: row.transfer.timestamp,
                            amount = it,
                        )
                    }
                },
            tradeDedupePolicy = strategy.tradeDedupePolicy(),
            dedupePolicy = strategy.dedupePolicy(unidentifiedCounterpartyTypeId),
            strategyId = strategy.id.toString(),
            uniqueKeyExtractor =
                if (uniqueIdTypeNames.isEmpty()) {
                    null
                } else {
                    ExistingUniqueKeyExtractor { transfer ->
                        transfer.attributes
                            .filter { it.attributeType.name in uniqueIdTypeNames }
                            .associate { it.attributeType.name to it.value }
                    }
                },
        )

    val importResult = importEngine.import(batch, onProgress = onProgress, batchSize = engineBatchSize)
    // After the trades exist, not with them: were the write to fail, the placeholder would stay counted and the next apply would
    // retire it, where excluding it first could leave neither it nor the real trade.
    if (supersedingExclusions.isNotEmpty()) importEngine.import(ImportBatch(tradeAttributeMutations = supersedingExclusions))

    val statusCounts =
        writeRowStatuses(importEngine, csvImport.id, importResult, assembledTradeRowIndexes, reconciledConversionRows.keys)
    val successCount = importResult.transfersImported + importResult.updated + statusCounts.newTrades
    val duplicateCount = importResult.duplicates + statusCounts.dedupedTrades

    logger.i {
        "Transfer import complete: $successCount imported/updated, $duplicateCount duplicate(s)"
    }

    // Refresh materialized views so transfers are visible (skipped in bulk; refreshed once at the end)
    if (refreshViews) {
        logger.i { "Refreshing materialized views" }
        maintenance.refreshMaterializedViews()
    }

    if ((successCount + duplicateCount + finalPrep.skippedRowIndexes.size) > 0) {
        recordCsvApplication(importEngine, csvImport.id, strategy, selectedSourceAccountId)
    }

    logger.i { "Import completed successfully" }

    // The engine import is atomic: any engine failure throws to the caller. The only per-row failures
    // are mapping errors detected before the import, so surface those to the dialog.
    return CsvImportResult(
        successCount = successCount,
        failedRows = finalPrep.errorRows.map { CsvImportResult.FailedRow(it.rowIndex, it.errorMessage) },
        duplicateCount = duplicateCount,
    )
}
