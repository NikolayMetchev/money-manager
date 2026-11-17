package com.moneymanager.importengineapi

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import kotlin.time.Duration.Companion.seconds

/** The attribute an exchange strategy stores each record's provider id under. */
const val EXCHANGE_TXN_ID_ATTR = "exchange.txn_id"

/**
 * How close an exchange's own trade and another source's record of it must be. An export folds an order's partial
 * fills into one row while the feed reports them one by one, so the window is the one trade dedupe compares within.
 */
val EXCHANGE_TRADE_RECONCILE_WINDOW = 5.seconds

/**
 * The dedupe policy for this strategy's records, which its importer and the observation matcher share: by the provider's
 * id, unique key or fields, reconciling against other sources within the strategy's windows, and bridging an exchange to
 * the owned accounts it moves money to directly ([bridges]).
 */
fun ApiImportStrategy.dedupePolicy(
    unidentifiedCounterpartyTypeId: AttributeTypeId?,
    bridges: List<AccountBridge> = emptyList(),
): DedupePolicy.ApiMultiKey {
    val reconcile = config.internalTransferReconcile
    return DedupePolicy.ApiMultiKey(
        // Cross-source reconciliation is unconditional, NOT derived from internalTransferReconcile: that config only
        // says which app account an exchange bridges to, while every source's statement export re-records the
        // movements its API returns.
        reconcileWindow = config.reconcileWindows.crossSource,
        reconciledExclusionAttributeTypeId = AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID),
        reconciledRelationshipTypeId = RelationshipTypeId(WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID),
        // An export cannot name the far end of a deposit or withdrawal, so it leaves a placeholder leg; this feed
        // knows it and supersedes it.
        unidentifiedCounterpartyAttributeTypeId = unidentifiedCounterpartyTypeId,
        unidentifiedCounterpartyWindow = config.reconcileWindows.placeholder,
        internalTransferBridges = bridges,
        internalTransferWindow = reconcile?.window,
        internalTransferAmountTolerance =
            reconcile?.amountTolerancePercent?.let { runCatching { BigDecimal(it) }.getOrNull() } ?: BigDecimal.ZERO,
    )
}

/**
 * The internal-transfer bridges of this strategy: its single account ([exchangeAccountId]) to each owned account its
 * `internalTransferReconcile` names that exists ([accountIdByName]).
 */
fun ApiImportStrategy.internalTransferBridges(
    exchangeAccountId: AccountId,
    accountIdByName: Map<String, AccountId>,
): List<AccountBridge> =
    config.internalTransferReconcile
        ?.bridges
        ?.mapNotNull { bridge -> accountIdByName[bridge.otherAccountName]?.let { AccountBridge(exchangeAccountId, it) } }
        .orEmpty()

/**
 * The trade policy for this strategy's trades: an exchange's fills are compared fuzzily with another source's trades,
 * a bank books none.
 */
fun ApiImportStrategy.tradeDedupePolicy(): TradeDedupePolicy =
    when (config.accounts) {
        is ApiAccountsSource.Single -> TradeDedupePolicy.Fuzzy(window = EXCHANGE_TRADE_RECONCILE_WINDOW)
        is ApiAccountsSource.Downloaded -> TradeDedupePolicy.ExactTupleOnly
    }

/** The attribute types (by name) this strategy's bank-feed rules mark as identifying a record. */
fun ApiImportStrategy.uniqueIdentifierAttributeNames(): List<String> =
    bankFeedAttributeActions().filter { it.uniqueIdentifier }.map { it.typeName }.distinct()

/** The attribute type (by name) holding the provider's own id for each of this strategy's records, if any. */
fun ApiImportStrategy.transactionIdAttributeName(): String? =
    when (config.accounts) {
        is ApiAccountsSource.Single -> {
            EXCHANGE_TXN_ID_ATTR
        }

        is ApiAccountsSource.Downloaded -> {
            bankFeedAttributeActions().firstOrNull { (it.value as? RuleValue.Read)?.expr?.paths == listOf("id") }?.typeName
        }
    }

/**
 * What the observation matcher needs to know about this strategy's records ([MatchFamily]), with attribute types
 * resolved through [attributeTypeIdByName] and accounts (the exchange's own and its bridges') through [accountIdByName].
 */
fun ApiImportStrategy.matchFamily(
    attributeTypeIdByName: Map<String, AttributeTypeId>,
    accountIdByName: Map<String, AccountId>,
): MatchFamily {
    val single = config.accounts as? ApiAccountsSource.Single
    val bridges = single?.let { accountIdByName[it.name] }?.let { internalTransferBridges(it, accountIdByName) }.orEmpty()
    return MatchFamily(
        dedupePolicy = dedupePolicy(attributeTypeIdByName[WellKnownIds.UNIDENTIFIED_COUNTERPARTY_ATTR_TYPE_NAME], bridges),
        tradePolicy = tradeDedupePolicy(),
        uniqueIdAttributeTypes = uniqueIdentifierAttributeNames().mapNotNullTo(mutableSetOf()) { attributeTypeIdByName[it] },
        apiIdAttributeType = transactionIdAttributeName()?.let { attributeTypeIdByName[it] },
    )
}

private fun ApiImportStrategy.bankFeedAttributeActions(): List<RuleAction.Attribute> =
    config.bankTransactions
        ?.transactionMappings
        ?.rules
        .orEmpty()
        .flatMap { it.actions }
        .filterIsInstance<RuleAction.Attribute>()
