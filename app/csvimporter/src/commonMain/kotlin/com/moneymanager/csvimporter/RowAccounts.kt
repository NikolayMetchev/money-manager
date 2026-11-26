package com.moneymanager.csvimporter

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.csvstrategy.AccountFromRulesMapping
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.RuleEvaluator
import com.moneymanager.domain.model.rules.RuleOutcome

/** Keeps each side's lookup and discovery under the same persisted-mapping decision. */
internal class RowAccounts(
    private val accounts: StatementAccounts,
    config: CsvStrategyConfig<FieldMapping>,
    outcome: RuleOutcome,
    values: RowValues,
    rules: RuleEvaluator,
    private val sourceOverride: AccountId?,
    private val targetMapping: FieldMapping,
    private val isTrade: Boolean,
    private val wholeFee: Boolean,
) {
    private val sourceMapping = config.fieldMappings[TransferField.SOURCE_ACCOUNT]
    private val source by lazy { accounts.resolveAccount(AccountSide.SOURCE, outcome, values) }
    private val target by lazy { accounts.resolveAccount(AccountSide.TARGET, outcome, values) }

    val ownId: AccountId
    val counterpartyId: AccountId
    val group: LegDetection?
    private val usePersistedSource: Boolean
    private val usePersistedTarget: Boolean

    init {
        val initialOwn =
            sourceOverride ?: accounts.parseAccount(
                sourceMapping ?: error("No source account selected. Please choose a source account before importing."),
                source,
            )
        val initialOther = if (wholeFee) OWN_FEE_ACCOUNT_ID else accounts.parseAccount(targetMapping, target)
        group = detectGroupLeg(config, accounts, rules, outcome, values)
        val intermediate = group?.throughAccountName
        val other = if (intermediate == null) initialOther else accounts.resolveExistingAccountId(intermediate) ?: UNRESOLVED_ACCOUNT_ID

        // A persisted mapping can claim both sides when they read the same column. Retry the own
        // side first, then the counterparty. Trades may exchange assets within one account.
        usePersistedSource = isTrade || sourceOverride != null || initialOwn != other || initialOwn == UNRESOLVED_ACCOUNT_ID
        ownId =
            if (usePersistedSource) {
                initialOwn
            } else {
                accounts.parseAccount(checkNotNull(sourceMapping), source, applyPersistedMappings = false)
            }
        usePersistedTarget = isTrade || intermediate != null || ownId != other || ownId == UNRESOLVED_ACCOUNT_ID
        counterpartyId =
            if (usePersistedTarget) {
                other
            } else {
                val fallback = accounts.parseAccount(targetMapping, target, applyPersistedMappings = false)
                if (fallback == UNRESOLVED_ACCOUNT_ID && targetMapping is AccountFromRulesMapping && target.accountName.isBlank()) {
                    error("Failed to resolve target account")
                }
                fallback
            }
    }

    val counterpartyName get() = target.accountName

    private fun usesTarget(detectedConduit: Boolean) = !detectedConduit && group?.throughAccountName == null && !wholeFee

    fun personalCounterparty(detectedConduit: Boolean): String? =
        if (usesTarget(detectedConduit)) accounts.resolvePersonalCounterparty(targetMapping, target, usePersistedTarget) else null

    fun unidentifiedCounterparty(
        strategyConduit: String?,
        detectedConduit: Boolean,
        person: String?,
    ): AccountId? =
        when {
            // On a strategy-conduit row the unknown party is the funder.
            strategyConduit != null -> {
                ownId.takeIf {
                    sourceOverride == null && sourceMapping != null &&
                        accounts.isUnidentifiedCounterparty(sourceMapping, source, usePersistedSource)
                }
            }

            isTrade || !usesTarget(detectedConduit) -> {
                null
            }

            else -> {
                counterpartyId.takeIf {
                    accounts.isUnidentifiedCounterparty(targetMapping, target, usePersistedTarget) ||
                        accounts.isOwnHolder(person ?: accounts.holderCandidate(targetMapping, target, usePersistedTarget), ownId)
                }
            }
        }

    fun discover(
        passThrough: CsvPassThrough?,
        detectedConduit: Boolean,
    ): Pair<List<NewAccount>, List<DiscoveredAccountMapping>> {
        val discoveries =
            listOfNotNull(
                if (sourceOverride == null &&
                    sourceMapping != null
                ) {
                    accounts.discoverNewAccount(sourceMapping, source, usePersistedSource)
                } else {
                    null
                },
                if (usesTarget(detectedConduit)) accounts.discoverNewAccount(targetMapping, target, usePersistedTarget) else null,
            )
        val category = (targetMapping as? AccountFromRulesMapping)?.defaultCategoryId ?: Category.UNCATEGORIZED_ID
        val newAccounts =
            buildList {
                discoveries.forEach { add(it.first) }
                group?.throughAccountName?.takeIf { accounts.resolveExistingAccountId(it) == null }?.let {
                    add(NewAccount(it, Category.UNCATEGORIZED_ID))
                }
                if (passThrough != null) {
                    for (name in passThrough.conduitNames) {
                        if (accounts.resolveExistingAccountId(name) == null) add(NewAccount(name, category))
                    }
                    if (detectedConduit && passThrough.merchantAccountId == null) add(NewAccount(passThrough.merchantName, category))
                }
            }
        return newAccounts to discoveries.map { it.second }
    }
}
