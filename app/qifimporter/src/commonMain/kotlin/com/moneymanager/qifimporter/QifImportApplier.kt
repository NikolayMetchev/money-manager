@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.qifimporter

import com.moneymanager.csvimporter.BulkImportResult
import com.moneymanager.csvimporter.CsvTransferMapper
import com.moneymanager.csvimporter.ImportPreparation
import com.moneymanager.csvimporter.buildAccountsToCreate
import com.moneymanager.csvimporter.buildFirstRowByAccountName
import com.moneymanager.csvimporter.buildPendingAccountMappings
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvColumn
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
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.normalizeNameKey
import kotlinx.coroutines.flow.first
import org.lighthousegames.logging.logging
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val logger = logging()

/** Summary of a bulk QIF import run across many files. */
data class QifBulkResult(
    override val filesImported: Int,
    override val transfersCreated: Int,
    override val duplicatesSkipped: Int,
    override val filesSkippedNoStrategy: Int,
    override val filesFailed: Int,
) : BulkImportResult

/**
 * Strategies usable for QIF imports: those whose identification columns are a (non-empty) subset of
 * QIF's fixed columns. Excludes CSV strategies (Wise/Monzo) whose columns aren't QIF columns.
 */
fun List<CsvImportStrategy>.qifCompatible(): List<CsvImportStrategy> {
    val qifHeaders = QifCsvAdapter.headers.toSet()
    return filter { it.identificationColumns.isNotEmpty() && it.identificationColumns.all { col -> col in qifHeaders } }
}

/** Number of leading rows sampled when content-matching a QIF file against a strategy. */
private const val QIF_CONTENT_SAMPLE_SIZE = 50

/**
 * Picks the QIF strategy whose [CsvImportStrategy.contentMatchRules] best fit the given [rows], so a
 * bank-specific strategy is auto-detected from the data (QIF's fixed columns can't distinguish banks).
 * A strategy with no content rules acts as the fallback: when nothing positively matches, the
 * rule-less strategy is returned. Ties are broken deterministically by score, then name, then id.
 * Returns null only when the receiver is empty.
 */
fun List<CsvImportStrategy>.selectForQifContent(
    rows: List<CsvRow>,
    columns: List<CsvColumn>,
): CsvImportStrategy? {
    if (isEmpty()) return null
    val indexByName = columns.associate { it.originalName to it.columnIndex }
    val sample = rows.take(QIF_CONTENT_SAMPLE_SIZE)

    fun CsvImportStrategy.contentScore(): Int {
        if (contentMatchRules.isEmpty()) return 0
        val compiled = contentMatchRules.map { it.columnName to Regex(it.pattern, RegexOption.IGNORE_CASE) }
        return sample.count { row ->
            compiled.any { (columnName, regex) ->
                val idx = indexByName[columnName] ?: return@any false
                row.values.getOrNull(idx)?.let { regex.containsMatchIn(it) } == true
            }
        }
    }

    val byScore =
        compareByDescending<Pair<CsvImportStrategy, Int>> { it.second }
            .thenBy { it.first.name }
            .thenBy { it.first.id.toString() }
    val best =
        map { it to it.contentScore() }
            .filter { it.second > 0 }
            .minWithOrNull(byScore)
    if (best != null) return best.first

    return filter { it.contentMatchRules.isEmpty() }
        .minWithOrNull(compareBy({ it.name }, { it.id.toString() }))
        ?: first()
}

/**
 * Applies the matching QIF strategy to every [imports] file using a single [sourceAccountId] and
 * [currencyId]. Payee/counterparty accounts are auto-created with their detected names (no per-file
 * confirmation). Refreshes materialized views once at the end. Reports progress via [onProgress].
 */
@Suppress("LongParameterList")
suspend fun bulkApplyQif(
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
        try {
            val count = qifImportRepository.countRecords(qifImport.id)
            val records = qifImportRepository.getImportRecords(qifImport.id, count.coerceAtLeast(1), 0)
            val rows = QifCsvAdapter.toRows(records)
            if (rows.isEmpty()) return@forEachIndexed

            // Auto-detect the bank strategy from the file's content (Santander vs the generic fallback).
            val matched = qifStrategies.selectForQifContent(rows, QifCsvAdapter.columns)
            if (matched == null) {
                skippedNoStrategy++
                return@forEachIndexed
            }

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
fun CsvImportStrategy.withQifCurrency(currencyId: CurrencyId?): CsvImportStrategy {
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

fun buildMapper(
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
suspend fun recordApplication(
    qifImportRepository: QifImportRepository,
    qifImport: QifImport,
    strategy: CsvImportStrategy,
) {
    runCatching {
        qifImportRepository.recordImportApplication(qifImport.id, strategy.id, strategy.name, Clock.System.now())
    }.onFailure { logger.warn { "Could not record QIF import application: ${it.message}" } }
}

@Suppress("LongParameterList", "LongMethod")
suspend fun runImport(
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
        // Record QIF provenance for each created account, stamping the first record that referenced it
        // so the audit trail can link back to that exact QIF record (atomically, in the repository).
        // QIF record indexes ARE the CSV-engine row indexes, so the shared helper gives us the first
        // record that referenced each account.
        val firstRecordByAccountName = buildFirstRowByAccountName(basePrep, selectedNewAccountNames)
        runCatching {
            accountRepository.createAccountsBatch(newAccounts) { account ->
                Source.Qif(qifImport.id, firstRecordByAccountName[account.name])
            }
        }.onFailure { logger.warn(it) { "Failed to bulk-create accounts" } }
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
                fromAccount = AccountRef.Existing(row.transfer.sourceAccountId),
                toAccount = AccountRef.Existing(row.transfer.targetAccountId),
                source = Source.Qif(qifImport.id),
                timestamp = row.transfer.timestamp,
                description = row.transfer.description,
                amount = row.transfer.amount,
                attributes = attributesFor(row.attributes),
            )
        }
    // Personal counterparties (resolved via person-flagged strategy rules) become People with an
    // ownership link to their counterparty account, in addition to the account itself. The engine
    // dedups people by name key and ownerships by (person, account), so repeats across rows/files and
    // re-imports collapse.
    val accountIdByName = latestAccounts.associate { it.name to it.id }
    // The first QIF record that referenced each personal counterparty, so the person's audit source
    // links back to the exact originating row (the engine writes ImportPersonIntent.source verbatim).
    val firstRecordByPersonName =
        finalPrep.validTransfers
            .mapNotNull { row -> row.personalCounterpartyName?.takeIf(String::isNotBlank)?.let { it to row.rowIndex } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, indexes) -> indexes.min() }
    val personAccounts =
        firstRecordByPersonName.keys
            .mapNotNull { name -> accountIdByName[name]?.let { name to it } }
    val peopleToCreate =
        personAccounts.map { (name, _) ->
            val parts = name.trim().split(Regex("\\s+"))
            ImportPersonIntent(
                key = LocalPersonKey(name),
                match = PersonMatchKey.ByNameKey(normalizeNameKey(name)),
                firstName = parts.first(),
                lastName = parts.drop(1).joinToString(" ").ifBlank { null },
                source = Source.Qif(qifImport.id, firstRecordByPersonName[name]),
            )
        }
    val ownerships =
        personAccounts.map { (name, accountId) ->
            ImportOwnershipIntent(
                personKey = LocalPersonKey(name),
                account = AccountRef.Existing(accountId),
                source = Source.Qif(qifImport.id, firstRecordByPersonName[name]),
            )
        }
    val batch =
        ImportBatch(
            transfers = importTransfers,
            dedupePolicy = DedupePolicy.FuzzyAllFields(),
            peopleToCreate = peopleToCreate,
            ownerships = ownerships,
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

    // Count from the reconciled per-record statuses (a split record collapses to one status), so a
    // mixed split isn't double-counted. Updated records count as successes, like the CSV path.
    return QifImportResult(
        successCount = importedRecords.size + updatedRecords.size,
        duplicateCount = duplicateRecords.size,
        failedCount = finalPrep.errorRows.size,
    )
}
