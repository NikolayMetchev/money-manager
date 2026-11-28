package com.moneymanager.session

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.accountmapping.accountsCapturedByMapping
import com.moneymanager.regex.PortableRegex
import kotlinx.coroutines.flow.first

/** The existing accounts a saved mapping takes over, and the applied files to re-import so it moves their rows. */
data class AccountMappingReimport(
    val accounts: List<Account>,
    val reimport: StrategyReimport,
) {
    val isEmpty: Boolean get() = accounts.isEmpty() || reimport.isEmpty
}

/**
 * Finds what a mapping of [pattern] to [targetAccountId] (scoped to [strategyId], null = global) changes in
 * what is already imported: the accounts it captures ([accountsCapturedByMapping]) and every applied,
 * non-ignored file in the mapping's scope that created one of them or booked a transfer onto one. Mappings
 * only route file imports, so no API session is offered.
 */
suspend fun SessionImports.findAccountMappingReimport(
    pattern: PortableRegex,
    targetAccountId: AccountId,
    strategyId: CsvImportStrategyId?,
): AccountMappingReimport {
    val accountRepository = services.accounts.accountRepository
    val captured =
        accountsCapturedByMapping(
            pattern = pattern,
            targetAccountId = targetAccountId,
            accounts = accountRepository.getAllAccounts().first(),
            previousNames = accountRepository.getPreviousAccountNames(),
            shadowAccountIds = accountRepository.getAccountIdsByAttribute(WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID),
        )
    if (captured.isEmpty()) return AccountMappingReimport(emptyList(), StrategyReimport(emptyList(), emptySet()))
    val capturedIds = captured.mapTo(mutableSetOf()) { it.id }
    val transactions = services.transactions.transactionRepository
    val capturedTransfers =
        capturedIds.flatMapTo(mutableSetOf()) { id -> transactions.getTransactionsByAccount(id).first().map { it.id } }
    val csvImports = services.imports.csvImportRepository
    val files =
        csvImports.getAllImports().first().filter { file ->
            file.lastAppliedAt != null &&
                !file.ignored &&
                (strategyId == null || file.lastAppliedStrategyId == strategyId) &&
                (
                    csvImports.getAccountsCreatedByImport(file.id).any(capturedIds::contains) ||
                        csvImports.getTransferIdsCreatedByImport(file.id).any(capturedTransfers::contains)
                )
        }
    return AccountMappingReimport(captured, StrategyReimport(files, emptySet()))
}
