@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.csvimporter

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Builds persistent account mappings for accounts the user chose to map to existing accounts, from the
 * discovered mappings in [preparation]. Regex matches keep their pattern; exact matches become an
 * anchored pattern over the CSV value. Deduplicated so one mapping is created per rule.
 */
fun buildPendingAccountMappings(
    preparation: ImportPreparation,
    strategyId: CsvImportStrategyId,
    accountSelections: Map<String, AccountId>,
    now: Instant = Clock.System.now(),
): List<CsvAccountMapping> {
    if (accountSelections.isEmpty()) {
        return emptyList()
    }

    return preparation.validTransfers
        .asSequence()
        .flatMap { it.discoveredMappings }
        .filter { discoveredMapping -> discoveredMapping.targetAccountName in accountSelections }
        .map { discoveredMapping ->
            val selectedAccountId = accountSelections.getValue(discoveredMapping.targetAccountName)
            PendingAccountMappingKey(
                columnName = discoveredMapping.columnName,
                pattern =
                    discoveredMapping.matchedPattern
                        ?: "^${Regex.escape(discoveredMapping.csvValue)}$",
                accountId = selectedAccountId,
            )
        }.distinct()
        .toList()
        .mapIndexed { index, mapping ->
            CsvAccountMapping(
                id = -(index + 1).toLong(),
                strategyId = strategyId,
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
