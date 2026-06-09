@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens.qif

import com.moneymanager.database.csv.CsvTransferMapper
import com.moneymanager.database.csv.ExistingTransferInfo
import com.moneymanager.database.csv.ImportPreparation
import com.moneymanager.database.qif.QifCsvAdapter
import com.moneymanager.domain.EntitySource
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CsvAccountMappingRepository
import com.moneymanager.domain.repository.QifImportRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.ui.screens.csv.buildAccountsToCreate
import com.moneymanager.ui.screens.csv.buildPendingAccountMappings
import kotlinx.coroutines.flow.first
import org.lighthousegames.logging.logging
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val logger = logging()

/** Summary of a bulk QIF import run across many files. */
internal data class QifBulkResult(
    val filesImported: Int,
    val transfersCreated: Int,
    val duplicatesSkipped: Int,
    val filesSkippedNoStrategy: Int,
    val filesFailed: Int,
)

/**
 * Strategies usable for QIF imports: those whose identification columns are a (non-empty) subset of
 * QIF's fixed columns. Excludes CSV strategies (Wise/Monzo) whose columns aren't QIF columns.
 */
internal fun List<CsvImportStrategy>.qifCompatible(): List<CsvImportStrategy> {
    val qifHeaders = QifCsvAdapter.headers.toSet()
    return filter { it.identificationColumns.isNotEmpty() && it.identificationColumns.all { col -> col in qifHeaders } }
}

/**
 * Applies the matching QIF strategy to every [imports] file using a single [sourceAccountId] and
 * [currencyId]. Payee/counterparty accounts are auto-created with their detected names (no per-file
 * confirmation). Refreshes materialized views once at the end. Reports progress via [onProgress].
 */
@Suppress("LongParameterList")
internal suspend fun bulkApplyQif(
    imports: List<QifImport>,
    sourceAccountId: AccountId,
    currencyId: CurrencyId,
    strategies: List<CsvImportStrategy>,
    currencies: List<Currency>,
    csvAccountMappingRepository: CsvAccountMappingRepository,
    accountRepository: AccountRepository,
    transactionRepository: TransactionRepository,
    qifImportRepository: QifImportRepository,
    attributeTypeRepository: AttributeTypeRepository,
    maintenance: Maintenance,
    entitySource: EntitySource,
    onProgress: (done: Int, total: Int) -> Unit,
): QifBulkResult {
    val qifStrategies = strategies.qifCompatible()
    var filesImported = 0
    var transfers = 0
    var duplicates = 0
    var skippedNoStrategy = 0
    var failed = 0

    imports.forEachIndexed { index, qifImport ->
        onProgress(index, imports.size)
        val matched = qifStrategies.firstOrNull()
        if (matched == null) {
            skippedNoStrategy++
            return@forEachIndexed
        }
        try {
            val count = qifImportRepository.countRecords(qifImport.id)
            val records = qifImportRepository.getImportRecords(qifImport.id, count.coerceAtLeast(1), 0)
            val rows = QifCsvAdapter.toRows(records)
            if (rows.isEmpty()) return@forEachIndexed

            val strategy = matched.withQifCurrency(currencyId)
            // Re-fetch accounts so payee accounts created by earlier files are seen.
            val accounts = accountRepository.getAllAccounts().first()
            val mappings = csvAccountMappingRepository.getMappingsForStrategy(matched.id).first()
            val basePrep = buildMapper(strategy, accounts, currencies, mappings, sourceAccountId).prepareImport(rows)

            val result =
                runImport(
                    qifImport = qifImport,
                    rows = rows,
                    strategy = strategy,
                    basePrep = basePrep,
                    selectedExistingAccounts = emptyMap(),
                    selectedNewAccountNames = emptyMap(),
                    selectedSourceAccountId = sourceAccountId,
                    currencies = currencies,
                    csvAccountMappingRepository = csvAccountMappingRepository,
                    accountRepository = accountRepository,
                    transactionRepository = transactionRepository,
                    qifImportRepository = qifImportRepository,
                    attributeTypeRepository = attributeTypeRepository,
                    maintenance = maintenance,
                    entitySource = entitySource,
                    refreshViews = false,
                )
            filesImported++
            transfers += result.successCount
            duplicates += result.duplicateCount
        } catch (expected: Exception) {
            logger.error(expected) { "Bulk QIF import failed for ${qifImport.originalFileName}: ${expected.message}" }
            failed++
        }
    }

    onProgress(imports.size, imports.size)
    maintenance.refreshMaterializedViews()

    return QifBulkResult(
        filesImported = filesImported,
        transfersCreated = transfers,
        duplicatesSkipped = duplicates,
        filesSkippedNoStrategy = skippedNoStrategy,
        filesFailed = failed,
    )
}

/**
 * Overrides the strategy's CURRENCY mapping with a hard-coded [currencyId], since QIF data has no
 * currency. When [currencyId] is null the strategy is returned unchanged (the import button is
 * disabled until a currency is chosen).
 */
internal fun CsvImportStrategy.withQifCurrency(currencyId: CurrencyId?): CsvImportStrategy {
    if (currencyId == null) return this
    return copy(
        fieldMappings =
            fieldMappings +
                (
                    TransferField.CURRENCY to
                        HardCodedCurrencyMapping(
                            id = FieldMappingId(Uuid.parse("00000000-0000-0000-0000-0000000c0de1")),
                            fieldType = TransferField.CURRENCY,
                            currencyId = currencyId,
                        )
                ),
    )
}

internal fun buildMapper(
    strategy: CsvImportStrategy,
    accounts: List<Account>,
    currencies: List<Currency>,
    accountMappings: List<CsvAccountMapping>,
    sourceAccountOverride: AccountId?,
    existingTransfers: List<ExistingTransferInfo> = emptyList(),
): CsvTransferMapper =
    CsvTransferMapper(
        strategy = strategy,
        columns = QifCsvAdapter.columns,
        existingAccounts = accounts.associateBy { it.name },
        existingCurrencies = currencies.associateBy { it.id },
        existingCurrenciesByCode = currencies.associateBy { it.code.uppercase() },
        existingTransfers = existingTransfers,
        accountMappings = accountMappings,
        sourceAccountOverride = sourceAccountOverride,
    )

/**
 * Loads existing transfers overlapping the import's accounts and date range so the mapper can flag
 * re-imported transactions as duplicates. QIF has no transaction id, so [CsvTransferMapper] matches
 * on date + accounts + amount + description (see its checkForDuplicateByAllFields).
 */
internal suspend fun loadExistingTransfers(
    prep: ImportPreparation,
    transactionRepository: TransactionRepository,
): List<ExistingTransferInfo> {
    if (prep.validTransfers.isEmpty()) return emptyList()
    val accountIds =
        prep.validTransfers.flatMap { listOf(it.transfer.sourceAccountId, it.transfer.targetAccountId) }.toSet()
    val minTs = prep.validTransfers.minOf { it.transfer.timestamp }
    val maxTs = prep.validTransfers.maxOf { it.transfer.timestamp }
    return accountIds
        .flatMap { id -> transactionRepository.getTransactionsByAccountAndDateRange(id, minTs, maxTs).first() }
        .distinctBy { it.id }
        .map { transfer ->
            ExistingTransferInfo(
                transferId = transfer.id,
                transfer = transfer,
                attributes = transfer.attributes.map { it.attributeType.name to it.value },
                uniqueIdentifierValues = emptyMap(),
            )
        }
}

/** Records that this strategy was applied to the import (so the import shows as imported). */
internal suspend fun recordApplication(
    qifImportRepository: QifImportRepository,
    qifImport: QifImport,
    strategy: CsvImportStrategy,
) {
    runCatching {
        qifImportRepository.recordImportApplication(qifImport.id, strategy.id, strategy.name, Clock.System.now())
    }.onFailure { logger.warn { "Could not record QIF import application: ${it.message}" } }
}

// Bulk transfer-creation mirrors the CSV ApplyStrategyDialog flow (shared import engine) by design.
@Suppress("LongParameterList", "LongMethod", "DuplicatedCode")
internal suspend fun runImport(
    qifImport: QifImport,
    rows: List<CsvRow>,
    strategy: CsvImportStrategy,
    basePrep: ImportPreparation,
    selectedExistingAccounts: Map<String, AccountId>,
    selectedNewAccountNames: Map<String, String>,
    selectedSourceAccountId: AccountId?,
    currencies: List<Currency>,
    csvAccountMappingRepository: CsvAccountMappingRepository,
    accountRepository: AccountRepository,
    transactionRepository: TransactionRepository,
    qifImportRepository: QifImportRepository,
    attributeTypeRepository: AttributeTypeRepository,
    maintenance: Maintenance,
    entitySource: EntitySource,
    refreshViews: Boolean = true,
): QifImportResult {
    // Persist any "map to existing account" selections so future imports reuse them.
    val selectedMappingsToPersist = buildPendingAccountMappings(basePrep, strategy.id, selectedExistingAccounts)
    if (selectedMappingsToPersist.isNotEmpty()) {
        runCatching { csvAccountMappingRepository.createMappings(selectedMappingsToPersist) }
            .onFailure { logger.warn(it) { "Failed to persist account mappings" } }
    }

    // Create any new accounts the user accepted.
    val accountsToCreate = buildAccountsToCreate(basePrep, selectedExistingAccounts, selectedNewAccountNames)
    if (accountsToCreate.isNotEmpty()) {
        val newAccounts =
            accountsToCreate.map { newAccount ->
                Account(id = AccountId(0), name = newAccount.name, openingDate = Clock.System.now(), categoryId = newAccount.categoryId)
            }
        runCatching { accountRepository.createAccountsBatch(newAccounts) }
            .onFailure { logger.warn(it) { "Failed to bulk-create accounts" } }
    }

    // Rebuild the mapper against the now-current accounts/mappings, then re-map with existing
    // transfers loaded so re-imported transactions are detected as duplicates and skipped.
    val latestAccounts = accountRepository.getAllAccounts().first()
    val latestMappings = csvAccountMappingRepository.getMappingsForStrategy(strategy.id).first()
    val discoveryPrep =
        buildMapper(strategy, latestAccounts, currencies, latestMappings, selectedSourceAccountId).prepareImport(rows)
    val existingTransfers = loadExistingTransfers(discoveryPrep, transactionRepository)
    val mapper = buildMapper(strategy, latestAccounts, currencies, latestMappings, selectedSourceAccountId, existingTransfers)
    val finalPrep = mapper.prepareImport(rows)

    // Surface mapping errors on their records.
    finalPrep.errorRows.forEach { errorRow ->
        qifImportRepository.updateRecordStatusesBatch(qifImport.id, ImportStatus.ERROR.name, mapOf(errorRow.rowIndex to null))
        qifImportRepository.saveError(qifImport.id, errorRow.rowIndex, errorRow.errorMessage)
    }

    val importedRows = finalPrep.validTransfers.filter { it.importStatus == ImportStatus.IMPORTED }
    val importedRecordIndexes = importedRows.map { it.rowIndex }.toSet()
    // Anything matching an existing transfer is skipped (not re-created). A split record counts as
    // imported if any of its rows were imported.
    val duplicateRows =
        finalPrep.validTransfers.filter { it.importStatus != ImportStatus.IMPORTED && it.rowIndex !in importedRecordIndexes }
    if (duplicateRows.isNotEmpty()) {
        qifImportRepository.updateRecordStatusesBatch(
            qifImport.id,
            ImportStatus.DUPLICATE.name,
            duplicateRows.associate { it.rowIndex to it.existingTransferId },
        )
    }
    if (importedRows.isEmpty()) {
        // Nothing new to create, but the strategy was still applied. Record it (unless the file was
        // entirely errors) so the import shows as imported rather than unimported.
        if (duplicateRows.isNotEmpty()) {
            recordApplication(qifImportRepository, qifImport, strategy)
        }
        return QifImportResult(successCount = 0, duplicateCount = duplicateRows.size, failedCount = finalPrep.errorRows.size)
    }

    // Pre-resolve attribute types.
    val attributeTypeIdByName =
        importedRows
            .flatMap { it.attributes }
            .map { it.first }
            .toSet()
            .associateWith { attributeTypeRepository.getOrCreate(it) }

    fun attributesFor(attributes: List<Pair<String, String>>): List<NewAttribute> =
        attributes.mapNotNull { (typeName, value) ->
            attributeTypeIdByName[typeName]?.let { NewAttribute(it, value) }
        }

    // Bulk-create transfers; the QIF recorder links each one back to its source record index.
    val transfersWithTempIds = importedRows.mapIndexed { index, row -> row.transfer.copy(id = TransferId(-(index + 1L))) }
    val attributesByTempId = importedRows.mapIndexed { index, row -> TransferId(-(index + 1L)) to attributesFor(row.attributes) }.toMap()
    var recorderCallIndex = 0
    val createdIds =
        transactionRepository.createTransfers(
            transfers = transfersWithTempIds,
            newAttributes = attributesByTempId,
            sourceRecorder =
                entitySource.qifImportRecorder(
                    qifImportId = qifImport.id,
                    recordIndexForTransfer = { importedRows[recorderCallIndex++].rowIndex },
                ),
            batchSize = transfersWithTempIds.size,
        )

    qifImportRepository.updateRecordStatusesBatch(
        qifImport.id,
        ImportStatus.IMPORTED.name,
        importedRows.mapIndexed { index, row -> row.rowIndex to createdIds.getOrNull(index) }.toMap(),
    )
    qifImportRepository.clearErrors(qifImport.id, importedRows.map { it.rowIndex })

    recordApplication(qifImportRepository, qifImport, strategy)

    if (refreshViews) {
        maintenance.refreshMaterializedViews()
    }

    return QifImportResult(
        successCount = importedRows.size,
        duplicateCount = duplicateRows.size,
        failedCount = finalPrep.errorRows.size,
    )
}
