package com.moneymanager.csvimporter

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.csvstrategy.AccountFromRulesMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.RuleEvaluator
import com.moneymanager.domain.model.rules.RuleOutcome
import com.moneymanager.importengineapi.PassThroughMatch
import com.moneymanager.importengineapi.normalizeNameKey

/**
 * How the rules named the account on one side for a row: the [naming] candidate that applied, the
 * [sourceValue] it read (what persisted account mappings match against), and the account it names —
 * [attributeAccountId] when an attribute candidate matched an account, otherwise [accountName] (blank
 * when no candidate applied or the value was blank).
 */
internal data class AccountResolution(
    val naming: AccountNaming?,
    val sourceValue: String,
    val accountName: String,
    val attributeAccountId: AccountId? = null,
    val personName: String? = null,
)

internal class StatementAccounts(
    strategy: CsvImportStrategy,
    private val existingAccounts: Map<String, Account>,
    historicalAccountNames: Map<String, AccountId>,
    accountMappings: List<AccountMapping>,
    accountOwnerNames: Map<AccountId, List<String>>,
    private val attributeAccountMatchers: Map<String, AttributeAccountMatcher>,
    private val rules: RuleEvaluator,
) {
    private val ownerNameKeys: Map<AccountId, Set<String>> =
        accountOwnerNames.mapValues { (_, names) -> names.mapTo(mutableSetOf(), ::normalizeNameKey) }

    // A reconciliation source (see ReconciliationConfig) must only ever resolve onto its own shadow
    // accounts, which the caller passes as [existingAccounts] (see accountsVisibleTo). Persisted mappings
    // and former names pointing anywhere else are dropped so they can't route a row onto a real account,
    // and pass-through detection (which rewrites onto real conduit/merchant accounts) is off.
    private val isReconciliationSource = strategy.config.reconciliation != null
    private val visibleAccountIds: Set<AccountId> = existingAccounts.values.mapTo(mutableSetOf()) { it.id }

    private val historicalAccountNames: Map<String, AccountId> =
        if (isReconciliationSource) historicalAccountNames.filterValues { it in visibleAccountIds } else historicalAccountNames

    // Only global mappings (strategyId null) and mappings scoped to THIS strategy apply; a
    // strategy-specific match is ordered ahead of a global one so it wins in findPersistedMapping
    // (which returns the first match).
    private val scopedAccountMappings: List<AccountMapping> =
        accountMappings
            .filter { it.strategyId == null || it.strategyId == strategy.id }
            .filter { !isReconciliationSource || it.accountId in visibleAccountIds }
            .sortedWith(compareBy({ it.strategyId == null }, { it.id }))

    private val accountsById: Map<AccountId, Account> by lazy {
        existingAccounts.values.associateBy { it.id }
    }

    // CSV account values repeat heavily across rows, and findPersistedMapping is called several
    // times per row — memoizing collapses an O(rows x mappings) regex scan to one pass per value.
    private val persistedMappingCache = HashMap<String, AccountId?>()

    /** The first of the rules' [side] candidates that applies to the row. */
    fun resolveAccount(
        side: AccountSide,
        outcome: RuleOutcome,
        values: RowValues,
    ): AccountResolution =
        outcome.accountsFor(side).firstNotNullOfOrNull { applyAccountNaming(it, values) }
            ?: AccountResolution(naming = null, sourceValue = "", accountName = "")

    /** What [naming] resolves this row to, or null when it doesn't apply and the next candidate should be tried. */
    fun applyAccountNaming(
        naming: AccountNaming,
        values: RowValues,
    ): AccountResolution? {
        val named = rules.name(naming) { values.required(it) } ?: return null
        val value = named.value
        if (naming.attributeTypeName != null) {
            val matched =
                value.takeIf { it.isNotBlank() }?.let { attributeAccountMatchers[naming.attributeTypeName]?.match(it) } ?: return null
            return AccountResolution(naming, value, value, attributeAccountId = matched)
        }
        return AccountResolution(naming, value, named.name, personName = named.personName)
    }

    /**
     * Resolves a field mapping to an account id. Persisted account mappings (the user's "map this CSV
     * value to that account" choices) are consulted first — they redirect a **counterparty** and handle
     * renamed accounts. [applyPersistedMappings] can be set false to bypass them when re-resolving a leg
     * a persisted mapping turned into a self-transfer: the **source** when a merchant mapping matched it
     * too (both legs read the same column), or the **counterparty** when a mapping sent it onto the
     * source's own account; see [mapRow].
     */
    fun parseAccount(
        mapping: FieldMapping,
        resolution: AccountResolution,
        applyPersistedMappings: Boolean = true,
    ): AccountId =
        when (mapping) {
            is HardCodedAccountMapping -> {
                mapping.accountId
            }

            is AccountFromRulesMapping -> {
                (if (applyPersistedMappings) findPersistedMapping(resolution) else null)
                    ?: resolution.attributeAccountId
                    ?: resolveExistingAccountId(resolution.accountName)
                    ?: UNRESOLVED_ACCOUNT_ID // Placeholder for new accounts
            }

            else -> {
                throw IllegalArgumentException("Invalid account mapping type: ${mapping::class}")
            }
        }

    /**
     * True when the candidate that named [mapping]'s account for this row declares the counterparty
     * unidentified (see [AccountNaming.unidentified]) and no persisted mapping claimed the
     * value — the user mapping the value to a real account is exactly what makes the counterparty known.
     * The account is then a placeholder for an unknown other end; see
     * `ImportTransfer.unidentifiedCounterpartyAccountId`.
     */
    fun isUnidentifiedCounterparty(
        mapping: FieldMapping,
        resolution: AccountResolution,
        applyPersistedMappings: Boolean = true,
    ): Boolean =
        mapping is AccountFromRulesMapping &&
            resolution.naming?.unidentified == true &&
            resolution.accountName.isNotBlank() &&
            !(applyPersistedMappings && findPersistedMapping(resolution) != null)

    /**
     * The persisted mapping for what the rules read on this side: tried against the raw [AccountResolution.sourceValue]
     * first, then against the [AccountResolution.accountName] the rules made of it. A mapping made by merging an account
     * (or typed by a person) names the account as it is called, "Revolut FROM Alex", while the source value is the whole
     * payee text it was extracted from, so matching the source value alone never applied it.
     */
    private fun findPersistedMapping(resolution: AccountResolution): AccountId? =
        findPersistedMapping(resolution.sourceValue)
            ?: resolution.accountName.takeIf { it.isNotBlank() && it != resolution.sourceValue }?.let { findPersistedMapping(it) }

    /**
     * Finds a persisted account mapping whose pattern matches the given account source value
     * (the value the strategy's account rule read for this row). First matching mapping wins:
     * strategy-scoped mappings are tried before global ones, then id order — so mappings that share a
     * pattern resolve deterministically.
     *
     * @param value The value to match against
     * @return The mapped AccountId, or null if no match found
     */
    private fun findPersistedMapping(value: String): AccountId? {
        if (value in persistedMappingCache) return persistedMappingCache[value]
        val result = scopedAccountMappings.firstOrNull { it.valuePattern.containsMatchIn(value) }?.accountId
        persistedMappingCache[value] = result
        return result
    }

    /**
     * Determines whether resolving [mapping] for this row requires creating a new account.
     * Returns the account to create together with the discovered mapping for auto-capture,
     * or null when no new account is needed (existing account, persisted mapping, attribute match, or a
     * blank name).
     */
    fun discoverNewAccount(
        mapping: FieldMapping,
        resolution: AccountResolution,
        applyPersistedMappings: Boolean = true,
    ): Pair<NewAccount, DiscoveredAccountMapping>? {
        val rulesMapping = mapping as? AccountFromRulesMapping ?: return null
        return when {
            applyPersistedMappings && findPersistedMapping(resolution) != null -> {
                null
            }

            resolution.attributeAccountId != null -> {
                null
            }

            resolution.accountName.isBlank() || resolveExistingAccountId(resolution.accountName) != null -> {
                null
            }

            else -> {
                NewAccount(resolution.accountName, rulesMapping.defaultCategoryId) to
                    DiscoveredAccountMapping(
                        csvValue = resolution.sourceValue,
                        targetAccountName = resolution.accountName,
                        matchedPattern = resolution.naming?.pattern,
                    )
            }
        }
    }

    /**
     * The person behind the counterparty (target) account for this row when its rule says it is one
     * (drives Person + ownership creation); null otherwise, or when a persisted account mapping
     * intentionally remaps the counterparty (e.g. onto an existing account).
     */
    fun resolvePersonalCounterparty(
        mapping: FieldMapping,
        resolution: AccountResolution,
        applyPersistedMappings: Boolean = true,
    ): String? {
        if (mapping !is AccountFromRulesMapping) return null
        return resolution.personName
            ?.takeIf { it.isNotBlank() && !(applyPersistedMappings && findPersistedMapping(resolution) != null) }
    }

    /**
     * The name a rule gave the counterparty, person or not: a strategy that doesn't mark its counterparties as
     * people (Revolut's "To <name>") still names the holder when the statement says "To <the holder>", so
     * [isOwnHolder] reads the account name too. A persisted mapping of the value still wins, and so does an account an
     * attribute rule resolved (its identity is known, whatever the statement calls it).
     */
    fun holderCandidate(
        mapping: FieldMapping,
        resolution: AccountResolution,
        applyPersistedMappings: Boolean,
    ): String? =
        resolution.accountName.takeIf {
            mapping is AccountFromRulesMapping &&
                resolution.attributeAccountId == null &&
                it.isNotBlank() &&
                !(applyPersistedMappings && findPersistedMapping(resolution) != null)
        }

    /**
     * True when [personalCounterpartyName] names an owner of [ownAccountId]: a statement saying only "FROM ALEX
     * SMITH" on Alex's own account records money moving between two of Alex's accounts without saying which, so
     * the other end is as unknown as an unidentified counterparty's.
     */
    fun isOwnHolder(
        personalCounterpartyName: String?,
        ownAccountId: AccountId,
    ): Boolean {
        val name = personalCounterpartyName?.takeIf { it.isNotBlank() } ?: return false
        return ownerNameKeys[ownAccountId]?.contains(normalizeNameKey(name)) == true
    }

    /**
     * Resolves an account name to an id: the current account by name first, then a renamed account via
     * its former name (audit history). Returns null when neither matches (a new account is needed).
     */
    fun resolveExistingAccountId(name: String): AccountId? = existingAccounts[name]?.id ?: historicalAccountNames[name.lowercase()]

    fun passThrough(
        passThroughMatch: PassThroughMatch?,
        strategyConduit: String?,
        description: String,
        sides: RowAccounts,
        incoming: Boolean,
    ): CsvPassThrough? {
        val prefix = listOfNotNull(strategyConduit)
        if (passThroughMatch == null) {
            if (strategyConduit == null) return null
            return CsvPassThrough(
                conduitNames = prefix,
                merchantName = accountsById[sides.counterpartyId]?.name ?: sides.counterpartyName,
                merchantAccountId = sides.counterpartyId.takeUnless { it == UNRESOLVED_ACCOUNT_ID },
                spendDescriptions = listOf(description),
                relationshipTypeId = WellKnownIds.PASS_THROUGH_RELATIONSHIP_TYPE_ID,
                incoming = incoming,
            )
        }
        val conduits = prefix + passThroughMatch.accounts.map { it.conduitAccountName }
        // A mapping to one of the conduits would book the merchant leg back into the chain.
        val merchant = passThroughMatch.merchantName
        val mapped =
            findPersistedMapping(merchant)?.let { accountsById[it] }?.takeUnless { account ->
                conduits.any { resolveExistingAccountId(it) == account.id }
            }
        return CsvPassThrough(
            conduitNames = conduits,
            merchantName = mapped?.name ?: merchant,
            merchantAccountId = mapped?.id ?: resolveExistingAccountId(merchant),
            spendDescriptions =
                listOfNotNull(description.takeIf { strategyConduit != null }) + passThroughMatch.hops.map { it.merchantText },
            relationshipTypeId = passThroughMatch.accounts.first().relationshipTypeId,
            incoming = incoming,
        )
    }
}
