@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.csvimporter

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.accountmapping.AccountMapping
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Builds persistent account mappings for accounts the user chose to map to existing accounts, from the
 * discovered mappings in [preparation]. Regex matches keep their pattern; exact matches become an
 * anchored pattern over the CSV value. Deduplicated so one mapping is created per rule.
 *
 * An exact match whose CSV value equals the chosen account's current name is skipped: plain name
 * lookup already resolves it, and a later rename is handled via audit history.
 */
fun buildPendingAccountMappings(
    preparation: ImportPreparation,
    accountSelections: Map<String, AccountId>,
    accountsById: Map<AccountId, Account> = emptyMap(),
    now: Instant = Clock.System.now(),
): List<AccountMapping> {
    if (accountSelections.isEmpty()) {
        return emptyList()
    }

    return preparation.validTransfers
        .asSequence()
        .flatMap { it.discoveredMappings }
        .filter { discoveredMapping -> discoveredMapping.targetAccountName in accountSelections }
        .mapNotNull { discoveredMapping ->
            val selectedAccountId = accountSelections.getValue(discoveredMapping.targetAccountName)
            // Case-sensitive on purpose: current-name resolution (CsvTransferMapper.resolveExistingAccountId)
            // matches by exact key, so a case-only difference is NOT redundant and must be persisted.
            val isRedundantExactMatch =
                discoveredMapping.matchedPattern == null &&
                    discoveredMapping.csvValue == accountsById[selectedAccountId]?.name
            if (isRedundantExactMatch) {
                null
            } else {
                PendingAccountMappingKey(
                    columnName = discoveredMapping.columnName,
                    pattern =
                        discoveredMapping.matchedPattern
                            ?: "^${Regex.escape(discoveredMapping.csvValue)}$",
                    accountId = selectedAccountId,
                )
            }
        }.distinct()
        .toList()
        .mapIndexed { index, mapping ->
            AccountMapping(
                id = -(index + 1).toLong(),
                columnName = mapping.columnName,
                valuePattern = Regex(mapping.pattern, RegexOption.IGNORE_CASE),
                accountId = mapping.accountId,
                createdAt = now,
                updatedAt = now,
            )
        }
}

fun buildCreatedAccountNameOverrides(
    preparation: ImportPreparation?,
    existingAccountSelections: Map<String, AccountId>,
    newAccountNames: Map<String, String>,
): Map<String, String> {
    val safePreparation = preparation ?: return emptyMap()
    return safePreparation.newAccounts
        .filter { it.name !in existingAccountSelections }
        .mapNotNull { account ->
            val renamed = newAccountNames[account.name]?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            account.name to renamed
        }.toMap()
}

fun buildAccountsToCreate(
    preparation: ImportPreparation,
    existingAccountSelections: Map<String, AccountId>,
    newAccountNames: Map<String, String>,
): List<NewAccount> =
    preparation.newAccounts
        .asSequence()
        .filter { it.name !in existingAccountSelections }
        .mapNotNull { account ->
            // A missing entry means the user kept the detected name
            val finalName =
                (newAccountNames[account.name] ?: account.name)
                    .trim()
                    .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NewAccount(
                name = finalName,
                categoryId = account.categoryId,
            )
        }.distinctBy { it.name }
        .toList()

fun hasBlankNewAccountNames(
    preparation: ImportPreparation?,
    existingAccountSelections: Map<String, AccountId>,
    newAccountNames: Map<String, String>,
): Boolean {
    val safePreparation = preparation ?: return false
    return safePreparation.newAccounts.any { account ->
        account.name !in existingAccountSelections &&
            // A missing entry means the user kept the detected name; only an explicit blank blocks
            (newAccountNames[account.name] ?: account.name).isBlank()
    }
}

private data class PendingAccountMappingKey(
    val columnName: String,
    val pattern: String,
    val accountId: AccountId,
)
