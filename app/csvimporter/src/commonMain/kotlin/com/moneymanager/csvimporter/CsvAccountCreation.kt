package com.moneymanager.csvimporter

import co.touchlab.kermit.Logger
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.createAccountMapping
import com.moneymanager.importengineapi.createAccountMappings
import com.moneymanager.importengineapi.createAccounts
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

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
