package com.moneymanager.csvimporter

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.repository.AccountReadRepository
import kotlinx.coroutines.flow.first

/**
 * The accounts [strategy]'s rows may resolve onto: every account for an ordinary strategy, but only the
 * source's shadow accounts for a reconciliation source (see `ReconciliationConfig`), so its data can
 * never land on — or be deduped against — a real account. [CsvTransferMapper] derives the rest of its
 * scoping (persisted mappings, former names) from this list.
 */
suspend fun AccountReadRepository.accountsVisibleTo(strategy: CsvImportStrategy): List<Account> {
    val all = getAllAccounts().first()
    val sourceName = strategy.config.reconciliation?.sourceName ?: return all
    val shadowIds = getAccountIdsByAttribute(WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID, sourceName)
    return all.filter { it.id in shadowIds }
}

/** The reconciliation source [this] strategy imports into shadow accounts, or null for an ordinary one. */
val CsvImportStrategy.shadowSource: String?
    get() = config.reconciliation?.sourceName
