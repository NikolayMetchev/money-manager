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
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.csvstrategy.isQifStrategy
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.QifImportReadRepository
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
import com.moneymanager.importengineapi.QifImportMutation
import com.moneymanager.importengineapi.applyQifImportMutations
import com.moneymanager.importengineapi.createAccountMappings
import com.moneymanager.importengineapi.createAccounts
import com.moneymanager.importengineapi.getOrCreateAttributeTypes
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
fun List<CsvImportStrategy>.qifCompatible(): List<CsvImportStrategy> = filter { it.isQifStrategy() }

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
 * Applies the matching QIF strategy to every [imports] file using a single [sourceAccountId]. The
 * currency comes from each file's auto-detected strategy (QIF data has none, so the strategy's
 * configured currency is authoritative), so there is no per-import currency prompt. Payee/counterparty
 * accounts are auto-created with their detected names (no per-file confirmation). Refreshes
 * materialized views once at the end. Reports progress via [onProgress].
 */
@Suppress("LongParameterList")
suspend fun bulkApplyQif(
    imports: List<QifImport>,
    sourceAccountId: AccountId,
    strategies: List<CsvImportStrategy>,
    currencies: List<Currency>,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    qifImportRepository: QifImportReadRepository,
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

            // The strategy's own (configured) currency is used — no per-import override.
            val strategy = matched
            // Re-fetch accounts so payee accounts created by earlier files are seen.
            val accounts = accountRepository.getAllAccounts().first()
            val mappings = accountMappingRepository.getAllMappings().first()
            val historicalAccountNames = accountRepository.getPreviousAccountNames()
            val basePrep =
                buildMapper(strategy, accounts, currencies, mappings, sourceAccountId, historicalAccountNames).prepareImport(rows)

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
                    accountMappingRepository = accountMappingRepository,
                    accountRepository = accountRepository,
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
    accountMappings: List<AccountMapping>,
    sourceAccountOverride: AccountId?,
    historicalAccountNames: Map<String, AccountId> = emptyMap(),
): CsvTransferMapper =
    CsvTransferMapper(
        strategy = strategy,
        columns = QifCsvAdapter.columns,
        existingAccounts = accounts.associateBy { it.name },
        existingCurrencies = currencies.associateBy { it.id },
        existingCurrenciesByCode = currencies.associateBy { it.code.uppercase() },
        accountMappings = accountMappings,
        historicalAccountNames = historicalAccountNames,
        sourceAccountOverride = sourceAccountOverride,
    )

/** Records that this strategy was applied to the import (so the import shows as imported). */
suspend fun recordApplication(
    importEngine: ImportEngine,
    qifImport: QifImport,
    strategy: CsvImportStrategy,
) {
    runCatching {
        importEngine.applyQifImportMutations(
            listOf(QifImportMutation.RecordApplication(qifImport.id, strategy.id, strategy.name, Clock.System.now())),
        )
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
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    refreshViews: Boolean = true,
): QifImportResult {
    // Persist any "map to existing account" selections so future imports reuse them.
    val selectedMappingsToPersist =
        buildPendingAccountMappings(
            basePrep,
            selectedExistingAccounts,
            accountRepository.getAllAccounts().first().associateBy { it.id },
        )
    if (selectedMappingsToPersist.isNotEmpty()) {
        runCatching { importEngine.createAccountMappings(selectedMappingsToPersist) }
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
            importEngine.createAccounts(newAccounts) { account ->
                Source.Qif(qifImport.id, firstRecordByAccountName[account.name])
            }
        }.onFailure { logger.warn(it) { "Failed to bulk-create accounts" } }
    }

    // Rebuild the mapper against the now-current accounts/mappings. Duplicate detection happens inside
    // the central engine, so no existing transfers are loaded here.
    val latestAccounts = accountRepository.getAllAccounts().first()
    val latestMappings = accountMappingRepository.getAllMappings().first()
    val historicalAccountNames = accountRepository.getPreviousAccountNames()
    val mapper =
        buildMapper(strategy, latestAccounts, currencies, latestMappings, selectedSourceAccountId, historicalAccountNames)
    val finalPrep = mapper.prepareImport(rows)

    // Surface mapping errors on their records.
    importEngine.applyQifImportMutations(
        finalPrep.errorRows.flatMap { errorRow ->
            listOf(
                QifImportMutation.UpdateRecordStatuses(qifImport.id, ImportStatus.ERROR.name, mapOf(errorRow.rowIndex to null)),
                QifImportMutation.SaveError(qifImport.id, errorRow.rowIndex, errorRow.errorMessage),
            )
        },
    )

    if (finalPrep.validTransfers.isEmpty()) {
        return QifImportResult(successCount = 0, failedCount = finalPrep.errorRows.size)
    }

    // Pre-resolve attribute types.
    val attributeTypeIdByName =
        importEngine.getOrCreateAttributeTypes(
            finalPrep.validTransfers
                .flatMap { it.attributes }
                .map { it.first }
                .toSet()
                .toList(),
        )

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
    //
    // The counterparty account id is read from the resolved transfer (the side that isn't the source
    // account) rather than by name lookup, so a counterparty that resolved to an existing account with
    // different casing/name still gets its Person + ownership. The value is paired with the first QIF
    // record that referenced the person, so the person's audit source links back to that exact row
    // (the engine writes ImportPersonIntent.source verbatim).
    val accountIdByName = latestAccounts.associate { it.name to it.id }
    // name -> (counterparty account id, first referencing record index)
    val personLinks = LinkedHashMap<String, Pair<AccountId, Long>>()
    finalPrep.validTransfers.forEach { row ->
        val name = row.personalCounterpartyName?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
        val counterpartyAccountId =
            when (selectedSourceAccountId) {
                row.transfer.sourceAccountId -> row.transfer.targetAccountId
                row.transfer.targetAccountId -> row.transfer.sourceAccountId
                else -> accountIdByName[name]
            } ?: return@forEach
        val previous = personLinks[name]
        personLinks[name] =
            (previous?.first ?: counterpartyAccountId) to minOf(previous?.second ?: row.rowIndex, row.rowIndex)
    }
    val peopleToCreate =
        personLinks.map { (name, link) ->
            val parts = name.split(Regex("\\s+"))
            ImportPersonIntent(
                key = LocalPersonKey(name),
                match = PersonMatchKey.ByNameKey(normalizeNameKey(name)),
                firstName = parts.first(),
                lastName = parts.drop(1).joinToString(" ").ifBlank { null },
                source = Source.Qif(qifImport.id, link.second),
            )
        }
    val ownerships =
        personLinks.map { (name, link) ->
            ImportOwnershipIntent(
                personKey = LocalPersonKey(name),
                account = AccountRef.Existing(link.first),
                source = Source.Qif(qifImport.id, link.second),
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
    val statusMutations = mutableListOf<QifImportMutation>()
    if (importedRecords.isNotEmpty()) {
        statusMutations += QifImportMutation.UpdateRecordStatuses(qifImport.id, ImportStatus.IMPORTED.name, importedRecords)
        statusMutations += QifImportMutation.ClearErrors(qifImport.id, importedRecords.keys.toList())
    }
    if (updatedRecords.isNotEmpty()) {
        statusMutations += QifImportMutation.UpdateRecordStatuses(qifImport.id, ImportStatus.UPDATED.name, updatedRecords)
        statusMutations += QifImportMutation.ClearErrors(qifImport.id, updatedRecords.keys.toList())
    }
    if (duplicateRecords.isNotEmpty()) {
        statusMutations += QifImportMutation.UpdateRecordStatuses(qifImport.id, ImportStatus.DUPLICATE.name, duplicateRecords)
        // A record retried from ERROR can resolve as DUPLICATE; clear its stale error like imported/updated do.
        statusMutations += QifImportMutation.ClearErrors(qifImport.id, duplicateRecords.keys.toList())
    }
    importEngine.applyQifImportMutations(statusMutations)

    if (importedRecords.isNotEmpty() || updatedRecords.isNotEmpty() || duplicateRecords.isNotEmpty()) {
        recordApplication(importEngine, qifImport, strategy)
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
