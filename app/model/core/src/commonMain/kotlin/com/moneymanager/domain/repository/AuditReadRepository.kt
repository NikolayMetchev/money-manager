@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountAttributeAuditEntry
import com.moneymanager.domain.model.AccountAuditEntry
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiImportStrategyAuditEntry
import com.moneymanager.domain.model.CategoryAuditEntry
import com.moneymanager.domain.model.CsvImportStrategyAuditEntry
import com.moneymanager.domain.model.CurrencyAuditEntry
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.ImportDirectoryAuditEntry
import com.moneymanager.domain.model.PersonAccountOwnershipAuditEntry
import com.moneymanager.domain.model.PersonAttributeAuditEntry
import com.moneymanager.domain.model.PersonAuditEntry
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.TransferAuditEntry
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.importdirectory.ImportDirectoryId

interface AuditReadRepository {
    /**
     * Gets the audit history for a specific transfer.
     * Returns entries ordered by audit timestamp descending (most recent first).
     * Each entry includes its source/provenance if available.
     *
     * @param transferId The ID of the transfer to get audit history for
     * @return List of audit entries for the transfer
     */
    suspend fun getAuditHistoryForTransfer(transferId: TransferId): List<TransferAuditEntry>

    /**
     * Gets the audit history for a specific account.
     * Returns entries ordered by audit timestamp descending (most recent first).
     * Each entry includes its source/provenance if available.
     *
     * @param accountId The ID of the account to get audit history for
     * @return List of audit entries for the account
     */
    suspend fun getAuditHistoryForAccount(accountId: AccountId): List<AccountAuditEntry>

    /**
     * Returns the last-known name of every account that has audit history, keyed by account id.
     * Lets audit screens label accounts that no longer exist (e.g. merged-away/deleted accounts)
     * by their name instead of a bare id.
     */
    suspend fun getLatestAuditedAccountNames(): Map<Long, String>

    /**
     * Gets the audit history for a specific person.
     * Returns entries ordered by audit timestamp descending (most recent first).
     * Each entry includes its source/provenance if available.
     *
     * @param personId The ID of the person to get audit history for
     * @return List of audit entries for the person
     */
    suspend fun getAuditHistoryForPerson(personId: PersonId): List<PersonAuditEntry>

    /**
     * Gets the attribute audit history for a specific person.
     * Returns all attribute changes (adds/updates/removes) for the given person.
     *
     * @param personId The ID of the person to get attribute audit history for
     * @return List of person attribute audit entries
     */
    suspend fun getAttributeAuditByPerson(personId: PersonId): List<PersonAttributeAuditEntry>

    /**
     * Gets the audit history for a specific person-account ownership.
     * Returns entries ordered by audit timestamp descending (most recent first).
     *
     * @param ownershipId The ID of the ownership to get audit history for
     * @return List of audit entries for the ownership
     */
    suspend fun getAuditHistoryForPersonAccountOwnership(ownershipId: Long): List<PersonAccountOwnershipAuditEntry>

    /**
     * Gets all ownership audit history for a specific account.
     * This returns all ownership changes (owners added/removed) for the given account.
     * Returns entries ordered by audit timestamp descending (most recent first).
     * Each entry includes its source/provenance if available.
     *
     * @param accountId The ID of the account to get ownership audit history for
     * @return List of ownership audit entries
     */
    suspend fun getOwnershipAuditHistoryForAccount(accountId: AccountId): List<PersonAccountOwnershipAuditEntry>

    /**
     * Gets the audit history for a specific currency.
     * Returns entries ordered by audit timestamp descending (most recent first).
     * Each entry includes its source/provenance if available.
     *
     * @param currencyId The ID of the currency to get audit history for
     * @return List of audit entries for the currency
     */
    suspend fun getAuditHistoryForCurrency(currencyId: CurrencyId): List<CurrencyAuditEntry>

    /**
     * Gets the audit history for a specific category.
     * Returns entries ordered by audit timestamp descending (most recent first).
     * Each entry includes its source/provenance if available.
     *
     * @param categoryId The ID of the category to get audit history for
     * @return List of audit entries for the category
     */
    suspend fun getAuditHistoryForCategory(categoryId: Long): List<CategoryAuditEntry>

    /**
     * Gets the attribute audit history for a specific account.
     * Returns all attribute changes (adds/updates/removes) for the given account.
     * Results are ordered by revision descending, then attribute type name.
     *
     * @param accountId The ID of the account to get attribute audit history for
     * @return List of attribute audit entries
     */
    suspend fun getAttributeAuditByAccount(accountId: AccountId): List<AccountAttributeAuditEntry>

    /**
     * Gets the audit history for a specific API import strategy.
     * Returns entries ordered by audit timestamp descending (most recent first).
     *
     * @param strategyId The ID of the strategy to get audit history for
     * @return List of audit entries for the strategy
     */
    suspend fun getAuditHistoryForApiImportStrategy(strategyId: ApiImportStrategyId): List<ApiImportStrategyAuditEntry>

    /**
     * Gets the audit history for a specific CSV import strategy.
     * Returns entries ordered by audit timestamp descending (most recent first).
     *
     * @param strategyId The ID of the strategy to get audit history for
     * @return List of audit entries for the strategy
     */
    suspend fun getAuditHistoryForCsvImportStrategy(strategyId: CsvImportStrategyId): List<CsvImportStrategyAuditEntry>

    /**
     * Gets the audit history for a specific import directory.
     * Returns entries ordered by audit timestamp descending (most recent first).
     *
     * @param directoryId The ID of the directory to get audit history for
     * @return List of audit entries for the directory
     */
    suspend fun getAuditHistoryForImportDirectory(directoryId: ImportDirectoryId): List<ImportDirectoryAuditEntry>
}
