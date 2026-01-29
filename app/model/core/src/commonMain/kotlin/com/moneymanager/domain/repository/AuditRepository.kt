@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountAuditEntry
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyAuditEntry
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.PersonAccountOwnershipAuditEntry
import com.moneymanager.domain.model.PersonAuditEntry
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.TransferAuditEntry
import com.moneymanager.domain.model.TransferId

interface AuditRepository {
    /**
     * Gets the audit history for a specific transfer.
     * Returns entries ordered by audit timestamp descending (most recent first).
     *
     * @param transferId The ID of the transfer to get audit history for
     * @return List of audit entries for the transfer
     */
    suspend fun getAuditHistoryForTransfer(transferId: TransferId): List<TransferAuditEntry>

    /**
     * Gets the audit history for a specific transfer with source information.
     * Returns entries ordered by audit timestamp descending (most recent first).
     * Each entry includes its source/provenance if available.
     *
     * @param transferId The ID of the transfer to get audit history for
     * @return List of audit entries with source information
     */
    suspend fun getAuditHistoryForTransferWithSource(transferId: TransferId): List<TransferAuditEntry>

    /**
     * Gets the audit history for a specific account.
     * Returns entries ordered by audit timestamp descending (most recent first).
     *
     * @param accountId The ID of the account to get audit history for
     * @return List of audit entries for the account
     */
    suspend fun getAuditHistoryForAccount(accountId: AccountId): List<AccountAuditEntry>

    /**
     * Gets the audit history for a specific account with source information.
     * Returns entries ordered by audit timestamp descending (most recent first).
     * Each entry includes its source/provenance if available.
     *
     * @param accountId The ID of the account to get audit history for
     * @return List of audit entries with source information
     */
    suspend fun getAuditHistoryForAccountWithSource(accountId: AccountId): List<AccountAuditEntry>

    /**
     * Gets the audit history for a specific person.
     * Returns entries ordered by audit timestamp descending (most recent first).
     *
     * @param personId The ID of the person to get audit history for
     * @return List of audit entries for the person
     */
    suspend fun getAuditHistoryForPerson(personId: PersonId): List<PersonAuditEntry>

    /**
     * Gets the audit history for a specific person with source information.
     * Returns entries ordered by audit timestamp descending (most recent first).
     * Each entry includes its source/provenance if available.
     *
     * @param personId The ID of the person to get audit history for
     * @return List of audit entries with source information
     */
    suspend fun getAuditHistoryForPersonWithSource(personId: PersonId): List<PersonAuditEntry>

    /**
     * Gets the audit history for a specific person-account ownership.
     * Returns entries ordered by audit timestamp descending (most recent first).
     *
     * @param ownershipId The ID of the ownership to get audit history for
     * @return List of audit entries for the ownership
     */
    suspend fun getAuditHistoryForPersonAccountOwnership(ownershipId: Long): List<PersonAccountOwnershipAuditEntry>

    /**
     * Gets all ownership audit history for a specific account with source information.
     * This returns all ownership changes (owners added/removed) for the given account.
     * Returns entries ordered by audit timestamp descending (most recent first).
     * Each entry includes its source/provenance if available.
     *
     * @param accountId The ID of the account to get ownership audit history for
     * @return List of ownership audit entries with source information
     */
    suspend fun getOwnershipAuditHistoryForAccountWithSource(accountId: AccountId): List<PersonAccountOwnershipAuditEntry>

    /**
     * Gets the audit history for a specific currency.
     * Returns entries ordered by audit timestamp descending (most recent first).
     *
     * @param currencyId The ID of the currency to get audit history for
     * @return List of audit entries for the currency
     */
    suspend fun getAuditHistoryForCurrency(currencyId: CurrencyId): List<CurrencyAuditEntry>

    /**
     * Gets the audit history for a specific currency with source information.
     * Returns entries ordered by audit timestamp descending (most recent first).
     * Each entry includes its source/provenance if available.
     *
     * @param currencyId The ID of the currency to get audit history for
     * @return List of audit entries with source information
     */
    suspend fun getAuditHistoryForCurrencyWithSource(currencyId: CurrencyId): List<CurrencyAuditEntry>
}
