package com.moneymanager.apiimporter

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import kotlinx.coroutines.flow.first

/**
 * The database's global account mappings, applied to the merchant a pass-through charge ends at. A bank feed names a
 * merchant through the conduit ("CRV*Revolut**4647* Lon London"), so the mapping a person made for it (by merging the
 * account, or typing it) must route the spend leg the way it routes a CSV row, rather than create an account named
 * after the stripped text. Only global mappings apply: a strategy-scoped mapping belongs to one CSV strategy.
 *
 * A mapping onto one of the chain's own conduits is ignored: the engine must never synthesise a conduit-to-conduit
 * spend leg.
 */
class MerchantMappings(
    private val mappings: List<AccountMapping>,
    private val conduitAccountIds: Set<AccountId>,
) {
    /** The account the first mapping matching any of [values] (in order) names, or null. */
    fun find(vararg values: String): AccountId? {
        for (value in values) {
            val mapped = mappings.firstOrNull { it.valuePattern.containsMatchIn(value) }?.accountId
            if (mapped != null && mapped !in conduitAccountIds) return mapped
        }
        return null
    }

    companion object {
        val NONE = MerchantMappings(emptyList(), emptySet())
    }
}

/** Loads the global mappings plus the ids of the accounts [passThroughAccounts] route through. */
internal suspend fun ApiImportReads.merchantMappings(passThroughAccounts: List<PassThroughAccount>): MerchantMappings {
    val global = globalAccountMappings()
    if (global.isEmpty() || passThroughAccounts.isEmpty()) return MerchantMappings(global, emptySet())
    val conduitNames = passThroughAccounts.map { it.conduitAccountName }.toSet()
    val conduitIds =
        accountRepository
            .getAllAccounts()
            .first()
            .filter { it.name in conduitNames }
            .map { it.id }
            .toSet()
    return MerchantMappings(global, conduitIds)
}

/** The mappings every import strategy shares, in id order (first match wins). */
internal suspend fun ApiImportReads.globalAccountMappings(): List<AccountMapping> =
    accountMappingRepository.getAllMappings().first().filter { it.strategyId == null }
