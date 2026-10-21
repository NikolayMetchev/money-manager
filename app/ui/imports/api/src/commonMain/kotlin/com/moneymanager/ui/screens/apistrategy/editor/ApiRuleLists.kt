package com.moneymanager.ui.screens.apistrategy.editor

import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.ui.components.rules.RuleContext

/** Which session sample a rule list's path fields pick from. */
internal enum class RulePathSource {
    ACCOUNT_SAMPLE,
    TRANSACTION_SAMPLE,

    /** Data endpoints have no session sample, so their paths are typed. */
    NONE,
}

/**
 * One of the rule lists an API strategy carries, as the Rules tab shows it: the downloaded accounts', the bank
 * feed's, or a data endpoint's. [withRules] puts an edited list back into a config.
 */
internal class ApiRuleList(
    val title: String,
    val context: RuleContext,
    val paths: RulePathSource,
    val rules: List<StrategyRule>,
    val withRules: ApiStrategyConfig.(List<StrategyRule>) -> ApiStrategyConfig,
)

/** Kinds whose records are interpreted by trade mappings; the rest use transaction mappings. */
internal val TRADE_KINDS = setOf(ApiEndpointKind.TRADES, ApiEndpointKind.ORDERS)

/**
 * Every rule list in this config, in the order the other tabs show what they apply to: accounts, the bank feed,
 * then each data endpoint numbered as the Endpoints tab numbers it.
 */
internal fun ApiStrategyConfig.ruleLists(): List<ApiRuleList> {
    val accountsRules =
        (accounts as? ApiAccountsSource.Downloaded)?.let {
            ApiRuleList(
                title = "Accounts",
                context = RuleContext.API_ACCOUNT,
                paths = RulePathSource.ACCOUNT_SAMPLE,
                rules = editedAccountMappings.rules,
                withRules = { rules -> withAccountMappings(editedAccountMappings.copy(rules = rules)) },
            )
        }
    val bankFeedRules =
        bankTransactions?.let { feed ->
            ApiRuleList(
                title = "Transactions (${feed.endpoint.path})",
                context = RuleContext.API_TRANSACTION,
                paths = RulePathSource.TRANSACTION_SAMPLE,
                rules = editedTransactionMappings.rules,
                withRules = { rules -> mapBankTransactionMappings { copy(rules = rules) } },
            )
        }
    val endpointRules =
        dataEndpoints
            .withIndex()
            .filter { it.value.kind != ApiEndpointKind.BANK_TRANSACTIONS }
            .mapIndexed { number, (index, endpoint) -> endpoint.ruleList(number + 1, index) }
    return listOfNotNull(accountsRules, bankFeedRules) + endpointRules
}

private fun ApiDataEndpoint.ruleList(
    number: Int,
    index: Int,
): ApiRuleList {
    val title = "$number. ${kind.name} (${endpoint.path})"

    fun ApiStrategyConfig.withEndpoint(transform: ApiDataEndpoint.() -> ApiDataEndpoint) =
        copy(dataEndpoints = dataEndpoints.mapIndexed { i, e -> if (i == index) e.transform() else e })
    return if (kind in TRADE_KINDS) {
        ApiRuleList(
            title = title,
            context = RuleContext.API_TRADE,
            paths = RulePathSource.NONE,
            rules = tradeMappings?.rules.orEmpty(),
            withRules = { rules ->
                withEndpoint { copy(tradeMappings = (tradeMappings ?: defaultTradeMappings()).copy(rules = rules)) }
            },
        )
    } else {
        ApiRuleList(
            title = title,
            context = RuleContext.API_TRANSACTION,
            paths = RulePathSource.NONE,
            rules = transactionMappings?.rules.orEmpty(),
            withRules = { rules ->
                withEndpoint { copy(transactionMappings = (transactionMappings ?: ApiTransactionMappings()).copy(rules = rules)) }
            },
        )
    }
}
