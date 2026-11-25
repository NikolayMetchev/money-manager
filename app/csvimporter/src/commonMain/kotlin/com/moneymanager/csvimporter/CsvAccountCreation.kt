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

private val logger = Logger.withTag("CsvAccountCreation")

/**
 * Saves [mappings] in a single batch, falling back to per-mapping saves if the atomic batch fails so
 * one bad mapping doesn't block the rest.
 */
internal suspend fun persistMappingsWithFallback(
    importEngine: ImportEngine,
    mappings: List<AccountMapping>,
) {
    if (mappings.isEmpty()) return
    try {
        importEngine.createAccountMappings(mappings)
        logger.i { "Saved ${mappings.size} account mappings" }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (expected: Exception) {
        // Batch is atomic, nothing was committed — retry per mapping so one bad mapping doesn't block the rest
        logger.w(expected) { "Bulk mapping save failed, falling back to per-mapping save" }
        for (mapping in mappings) {
            try {
                importEngine.createAccountMapping(
                    valuePattern = mapping.valuePattern,
                    accountId = mapping.accountId,
                    strategyId = mapping.strategyId,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expectedMappingError: Exception) {
                // Don't log the pattern itself — it's derived from bank-file payee/account values (PII).
                logger.w(expectedMappingError) { "Failed to save account mapping for account id ${mapping.accountId.id}" }
            }
        }
    }
}

/**
 * Creates the [accountsToCreate] (bulk, with per-account fallback) and returns the names actually
 * created. Failures are skipped — transfers referencing a missing account fail later.
 */
internal suspend fun createNewAccounts(
    importEngine: ImportEngine,
    accountsToCreate: List<NewAccount>,
    csvImportId: CsvImportId,
    firstRowByAccountName: Map<String, Long>,
    shadowSource: String?,
): Set<String> {
    if (accountsToCreate.isEmpty()) return emptySet()
    val createdAccountNames = mutableSetOf<String>()
    val newAccounts =
        accountsToCreate.map { newAccount ->
            Account(
                id = AccountId(0),
                name = newAccount.name,
                openingDate = Clock.System.now(),
                categoryId = newAccount.categoryId,
            )
        }
    // CSV provenance pointing each account at the first row that referenced it (its creation row);
    // row-less when unknown.
    val sourceFor: (Account) -> Source = { account ->
        Source.Csv(csvImportId, firstRowByAccountName[account.name])
    }
    try {
        // Bulk-create all accounts in a single engine batch
        importEngine.createAccounts(newAccounts, shadowSource = shadowSource, sourceFor = sourceFor)
        newAccounts.forEach { account -> createdAccountNames.add(account.name) }
        logger.i { "Created ${accountsToCreate.size} new accounts" }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (expected: Exception) {
        // Bulk create failed — fall back to per-account creation to skip only failing accounts
        logger.w(expected) { "Bulk account creation failed, falling back to per-account creation" }
        for (account in newAccounts) {
            try {
                importEngine.createAccount(account, sourceFor(account), shadowSource)
                createdAccountNames.add(account.name)
                logger.i { "Created new account: ${account.name}" }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expectedAccountError: Exception) {
                logger.w(expectedAccountError) { "Skipping account '${account.name}': ${expectedAccountError.message}" }
            }
        }
    }
    return createdAccountNames
}

/**
 * Maps each new account's final name to the index of the earliest row/record that referenced it (its
 * creation row), so account provenance can point at the relevant row. Keyed by the final (possibly
 * user-renamed) account name to match the accounts actually created. Shared with the QIF importer,
 * which reuses this CSV engine (QIF record indexes ARE these row indexes).
 */
fun buildFirstRowByAccountName(
    preparation: ImportPreparation,
    newAccountNames: Map<String, String>,
): Map<String, Long> {
    val firstRow = mutableMapOf<String, Long>()

    fun record(
        name: String,
        rowIndex: Long,
    ) {
        val finalName = name.trim()
        if (finalName.isNotBlank() && finalName !in firstRow) firstRow[finalName] = rowIndex
    }
    preparation.validTransfers
        .sortedBy { it.rowIndex }
        .forEach { transfer ->
            transfer.discoveredMappings.forEach { mapping ->
                record(newAccountNames[mapping.targetAccountName] ?: mapping.targetAccountName, transfer.rowIndex)
            }
            // Pass-through (conduit) rows create the conduit + merchant accounts outside the discovered-
            // mapping path, so record their originating row too — otherwise their provenance has no row
            // and the audit "Row" link points at the non-existent row 0.
            transfer.passThrough?.let { pt ->
                (pt.conduitNames + pt.merchantName).forEach { record(it, transfer.rowIndex) }
            }
        }
    return firstRow
}

/**
 * Turns any row still carrying [UNRESOLVED_ACCOUNT_ID] after the post-creation re-map into an error
 * row. By then every account a row needs exists, so a surviving placeholder means the row's account
 * could not be resolved; importing it would violate the transfer's account foreign key and abort the
 * whole file, losing the rows that are fine.
 */
internal fun ImportPreparation.withUnresolvedAccountsAsErrors(): ImportPreparation {
    val (unresolved, resolved) =
        validTransfers.partition {
            it.transfer.sourceAccountId == UNRESOLVED_ACCOUNT_ID || it.transfer.targetAccountId == UNRESOLVED_ACCOUNT_ID
        }
    if (unresolved.isEmpty()) return this
    logger.w { "Skipping ${unresolved.size} row(s) whose account could not be resolved after account creation" }
    return copy(
        validTransfers = resolved,
        errorRows =
            errorRows +
                unresolved.map {
                    MappingResult.Error(it.rowIndex, "Account could not be resolved; the row references an account that does not exist")
                },
    )
}
