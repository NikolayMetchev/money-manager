@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens.qif

import com.moneymanager.database.csv.CsvTransferMapper
import com.moneymanager.database.csv.ImportPreparation
import com.moneymanager.database.qif.QifCsvAdapter
import com.moneymanager.database.qif.QifImportProvenance
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
import com.moneymanager.importer.ImportEngine
import com.moneymanager.importmodel.AccountRef
import com.moneymanager.importmodel.DedupePolicy
import com.moneymanager.importmodel.ImportBatch
import com.moneymanager.importmodel.ImportRowKey
import com.moneymanager.importmodel.ImportTransfer
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
    qifImportRepository: QifImportRepository,
    attributeTypeRepository: AttributeTypeRepository,
    maintenance: Maintenance,
    entitySource: EntitySource,
    importEngine: ImportEngine,
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
                    qifImportRepository = qifImportRepository,
                    attributeTypeRepository = attributeTypeRepository,
                    maintenance = maintenance,
                    entitySource = entitySource,
                    importEngine = importEngine,
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
): CsvTransferMapper =
    CsvTransferMapper(
        strategy = strategy,
        columns = QifCsvAdapter.columns,
        existingAccounts = accounts.associateBy { it.name },
        existingCurrencies = currencies.associateBy { it.id },
        existingCurrenciesByCode = currencies.associateBy { it.code.uppercase() },
        accountMappings = accountMappings,
        sourceAccountOverride = sourceAccountOverride,
    )

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

@Suppress("LongParameterList", "LongMethod")
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
    qifImportRepository: QifImportRepository,
    attributeTypeRepository: AttributeTypeRepository,
    maintenance: Maintenance,
    entitySource: EntitySource,
    importEngine: ImportEngine,
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

    // Rebuild the mapper against the now-current accounts/mappings. Duplicate detection happens inside
    // the central engine, so no existing transfers are loaded here.
    val latestAccounts = accountRepository.getAllAccounts().first()
    val latestMappings = csvAccountMappingRepository.getMappingsForStrategy(strategy.id).first()
    val mapper = buildMapper(strategy, latestAccounts, currencies, latestMappings, selectedSourceAccountId)
    val finalPrep = mapper.prepareImport(rows)

    // Surface mapping errors on their records.
    finalPrep.errorRows.forEach { errorRow ->
        qifImportRepository.updateRecordStatusesBatch(qifImport.id, ImportStatus.ERROR.name, mapOf(errorRow.rowIndex to null))
        qifImportRepository.saveError(qifImport.id, errorRow.rowIndex, errorRow.errorMessage)
    }

    if (finalPrep.validTransfers.isEmpty()) {
        return QifImportResult(successCount = 0, failedCount = finalPrep.errorRows.size)
    }

    // Pre-resolve attribute types.
    val attributeTypeIdByName =
        finalPrep.validTransfers
            .flatMap { it.attributes }
            .map { it.first }
            .toSet()
            .associateWith { attributeTypeRepository.getOrCreate(it) }

    fun attributesFor(attributes: List<Pair<String, String>>): List<NewAttribute> =
        attributes.mapNotNull { (typeName, value) ->
            attributeTypeIdByName[typeName]?.let { NewAttribute(it, value) }
        }

    // Build the unified import batch. Accounts are already resolved/created above, so transfers carry
    // Existing refs. QIF splits expand to multiple transfers per record sharing one recordIndex, so a
    // per-record split index keeps the row keys unique. QIF has no transaction id -> fuzzy dedupe.
    val splitCounters = mutableMapOf<Long, Int>()
    val importTransfers =
        finalPrep.validTransfers.map { row ->
            val splitIndex = splitCounters.getOrElse(row.rowIndex) { 0 }
            splitCounters[row.rowIndex] = splitIndex + 1
            ImportTransfer(
                rowKey = ImportRowKey.QifRecord(row.rowIndex, splitIndex),
                source = AccountRef.Existing(row.transfer.sourceAccountId),
                target = AccountRef.Existing(row.transfer.targetAccountId),
                timestamp = row.transfer.timestamp,
                description = row.transfer.description,
                amount = row.transfer.amount,
                attributes = attributesFor(row.attributes),
            )
        }
    val batch =
        ImportBatch(
            transfers = importTransfers,
            dedupePolicy = DedupePolicy.FuzzyAllFields(),
            provenance = QifImportProvenance(entitySource, qifImport.id),
        )
    val importResult = importEngine.import(batch)

    // Reconcile per-record statuses: a record is IMPORTED if any of its split rows imported, else
    // UPDATED if any updated, else DUPLICATE.
    val importedRecords = mutableMapOf<Long, TransferId?>()
    val updatedRecords = mutableMapOf<Long, TransferId?>()
    val duplicateRecords = mutableMapOf<Long, TransferId?>()
    importResult.rowOutcomes.entries
        .groupBy { (it.key as ImportRowKey.QifRecord).recordIndex }
        .forEach { (recordIndex, entries) ->
            val outcomes = entries.map { it.value }
            val imported = outcomes.firstOrNull { it.status == ImportStatus.IMPORTED }
            val updated = outcomes.firstOrNull { it.status == ImportStatus.UPDATED }
            when {
                imported != null -> importedRecords[recordIndex] = imported.transferId
                updated != null -> updatedRecords[recordIndex] = updated.transferId
                else -> duplicateRecords[recordIndex] = outcomes.firstOrNull()?.transferId
            }
        }
    if (importedRecords.isNotEmpty()) {
        qifImportRepository.updateRecordStatusesBatch(qifImport.id, ImportStatus.IMPORTED.name, importedRecords)
        qifImportRepository.clearErrors(qifImport.id, importedRecords.keys.toList())
    }
    if (updatedRecords.isNotEmpty()) {
        qifImportRepository.updateRecordStatusesBatch(qifImport.id, ImportStatus.UPDATED.name, updatedRecords)
        qifImportRepository.clearErrors(qifImport.id, updatedRecords.keys.toList())
    }
    if (duplicateRecords.isNotEmpty()) {
        qifImportRepository.updateRecordStatusesBatch(qifImport.id, ImportStatus.DUPLICATE.name, duplicateRecords)
    }

    if (importedRecords.isNotEmpty() || updatedRecords.isNotEmpty() || duplicateRecords.isNotEmpty()) {
        recordApplication(qifImportRepository, qifImport, strategy)
    }

    if (refreshViews) {
        maintenance.refreshMaterializedViews()
    }

    return QifImportResult(
        successCount = importResult.transfersImported,
        duplicateCount = duplicateRecords.size + updatedRecords.size,
        failedCount = finalPrep.errorRows.size,
    )
}
