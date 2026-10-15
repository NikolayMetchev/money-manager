package com.moneymanager.query

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.AssetId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Accounts joined with their owners and balances, filtered by [AccountQueryCriteria]. The Accounts screen,
 * the CLI and the TUI all read accounts through this, so they agree on what a filter keeps.
 *
 * Balances come from the balance materialized view: a caller that needs them current (the CLI, after an
 * import) calls `Maintenance.refreshMaterializedViews()` before collecting.
 */
class AccountQuery(
    private val accountRepository: AccountReadRepository,
    private val accountAttributeRepository: AccountAttributeReadRepository,
    private val transactionRepository: TransactionReadRepository,
    private val personRepository: PersonReadRepository,
    private val personAccountOwnershipRepository: PersonAccountOwnershipReadRepository,
) {
    /** Every account, unfiltered. Collect this once and [AccountSnapshot.filter] it as the criteria change. */
    fun snapshot(): Flow<AccountSnapshot> =
        combine(
            accountRepository.getAllAccounts(),
            transactionRepository.getAccountBalances(),
            personRepository.getAllPeople(),
            personAccountOwnershipRepository.getAllOwnerships(),
            accountAttributeRepository.getByType(AttributeTypeId(WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID)),
        ) { accounts, balances, people, ownerships, shadowAttributes ->
            val peopleById = people.associateBy { it.id }
            val ownersByAccount =
                ownerships.groupBy({ it.accountId }, { it.personId }).mapValues { (_, ids) -> ids.mapNotNull { peopleById[it] } }
            val balancesByAccount = balances.groupBy({ it.accountId }, { it.balance })
            val shadowAccountIds = shadowAttributes.mapTo(mutableSetOf()) { it.accountId }
            AccountSnapshot(
                accounts =
                    accounts.map { account ->
                        AccountWithBalances(
                            account = account,
                            owners = ownersByAccount[account.id].orEmpty(),
                            balances = balancesByAccount[account.id].orEmpty(),
                            isShadow = account.id in shadowAccountIds,
                        )
                    },
                people = people,
            )
        }

    fun accountsWithBalances(criteria: AccountQueryCriteria): Flow<List<AccountWithBalances>> = snapshot().map { it.filter(criteria) }
}

data class AccountWithBalances(
    val account: Account,
    val owners: List<Person>,
    val balances: List<Money>,
    /** A reconciliation source's copy of an account (see [AccountQueryCriteria.includeShadow]). */
    val isShadow: Boolean,
)

data class AccountSnapshot(
    val accounts: List<AccountWithBalances>,
    val people: List<Person>,
) {
    /** Assets at least one account has a balance in, by code. */
    val availableAssets: List<Asset> by lazy {
        accounts
            .flatMap { it.balances }
            .map { it.asset }
            .distinctBy { it.id }
            .sortedBy { it.code }
    }

    val shadowAccountCount: Int by lazy { accounts.count { it.isShadow } }

    /**
     * The accounts [criteria] keeps, in their original order, each listing only the balances it keeps.
     *
     * @throws UnknownOwnerException if an [OwnerRef.Name] names nobody
     * @throws AmbiguousOwnerException if an [OwnerRef.Name] names several people
     */
    fun filter(criteria: AccountQueryCriteria): List<AccountWithBalances> {
        val ownerIds = criteria.owners.mapTo(mutableSetOf(), ::resolveOwner)
        val assetIds = criteria.assets.flatMapTo(mutableSetOf(), ::resolveAssets)
        return accounts.mapNotNull { entry ->
            val balances =
                entry.balances.filter { balance ->
                    (criteria.assets.isEmpty() || balance.asset.id in assetIds) && !(criteria.nonZeroOnly && balance.isZero())
                }
            entry.copy(balances = balances).takeIf {
                (criteria.includeShadow || !entry.isShadow) &&
                    (criteria.name == null || criteria.name.matches(entry.account.name)) &&
                    (ownerIds.isEmpty() || entry.owners.any { it.id in ownerIds }) &&
                    ((criteria.assets.isEmpty() && !criteria.nonZeroOnly) || balances.isNotEmpty())
            }
        }
    }

    /**
     * The one account [ref] names. A name only matches a reconciliation source's shadow accounts with
     * [includeShadow], as in [filter]; an id names its account either way.
     *
     * @throws UnknownAccountException if [ref] names no account
     * @throws AmbiguousAccountException if a [AccountRef.Name] matches several accounts
     */
    fun resolveAccount(
        ref: AccountRef,
        includeShadow: Boolean = false,
    ): AccountWithBalances =
        when (ref) {
            is AccountRef.Id -> accounts.firstOrNull { it.account.id == ref.id } ?: throw UnknownAccountException(ref)
            is AccountRef.Name -> {
                val matches = accounts.filter { (includeShadow || !it.isShadow) && ref.match.matches(it.account.name) }
                // A name that is exactly one account's wins over the others it is part of ("Cash" vs "Cash ISA").
                matches.singleOrNull()
                    ?: matches.singleOrNull { it.account.name.equals(ref.match.text, ignoreCase = true) }
                    ?: throw if (matches.isEmpty()) {
                        UnknownAccountException(ref)
                    } else {
                        AmbiguousAccountException(ref, matches.map { it.account })
                    }
            }
        }

    private fun resolveOwner(ref: OwnerRef): PersonId =
        when (ref) {
            is OwnerRef.Id -> ref.id
            is OwnerRef.Name -> {
                val matches = people.filter { it.fullName.equals(ref.name.trim(), ignoreCase = true) }
                when (matches.size) {
                    0 -> throw UnknownOwnerException(ref.name)
                    1 -> matches.single().id
                    else -> throw AmbiguousOwnerException(ref.name, matches)
                }
            }
        }

    private fun resolveAssets(ref: AssetRef): List<AssetId> =
        when (ref) {
            is AssetRef.Id -> listOf(ref.id)
            is AssetRef.Code -> availableAssets.filter { it.code.equals(ref.code, ignoreCase = true) }.map { it.id }
        }
}
