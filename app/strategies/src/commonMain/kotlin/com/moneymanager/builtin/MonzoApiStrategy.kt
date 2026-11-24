package com.moneymanager.builtin

import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiPaging
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.ForeignAmount
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant

internal object MonzoApiStrategy {
    /** The built-in Monzo API import strategy. */
    fun strategy(now: Instant): ApiImportStrategy =
        ApiImportStrategy(
            id = ApiImportStrategyId(BuiltInApiStrategies.monzoStrategyId),
            name = "Monzo",
            config =
                ApiStrategyConfig(
                    baseUrl = "https://api.monzo.com",
                    accounts =
                        ApiAccountsSource.Downloaded(
                            endpoint =
                                ApiEndpointConfig(
                                    path = "/accounts",
                                    responseArrayKey = "accounts",
                                ),
                            mappings =
                                ApiAccountMappings(
                                    ownerNameField = "preferred_name",
                                    // Monzo's account "description" is the account holder's own user id, not a
                                    // display name — Monzo's API has no field meant for this, so use a fixed name.
                                    rules =
                                        listOf(
                                            // Monzo's cashback/rewards opt-in is a pseudo-account with no bank details and
                                            // (for most users) no activity — it would otherwise collide with "Monzo".
                                            accounts(
                                                AccountSide.OWN,
                                                fixedNaming("id", "Monzo Rewards"),
                                                conditions = listOf(Condition("type", ConditionOp.EQUALS, value = "uk_rewards")),
                                            ),
                                            // A joint account has more than one owner.
                                            accounts(
                                                AccountSide.OWN,
                                                fixedNaming("id", "Monzo Joint"),
                                                conditions = listOf(Condition("owners[1].user_id", ConditionOp.EXISTS)),
                                            ),
                                            accounts(AccountSide.OWN, fixedNaming("id", "Monzo")),
                                        ),
                                ),
                        ),
                    dataEndpoints =
                        listOf(
                            ApiDataEndpoint(
                                endpoint =
                                    ApiEndpointConfig(
                                        path = "/transactions",
                                        responseArrayKey = "transactions",
                                        queryParams =
                                            listOf(
                                                ApiQueryParam(name = "account_id", dynamicSource = "account.id"),
                                            ),
                                        pagination = ApiPaginationConfig(paging = ApiPaging.BeforeCursor(), sendLimitParam = true),
                                    ),
                                kind = ApiEndpointKind.BANK_TRANSACTIONS,
                                transactionMappings =
                                    ApiTransactionMappings(
                                        counterpartyIdField = "counterparty.id",
                                        rules =
                                            monzoAtmRules +
                                                listOf(
                                                    accounts(AccountSide.COUNTERPARTY, naming("merchant.name", "counterparty.name")),
                                                    declinedWhen(Condition("decline_reason", ConditionOp.NOT_BLANK)),
                                                    rule(
                                                        RuleAction.CounterpartyIsPerson,
                                                        conditions =
                                                            listOf(
                                                                Condition(
                                                                    "counterparty.beneficiary_account_type",
                                                                    ConditionOp.EQUALS_IGNORE_CASE,
                                                                    value = "Personal",
                                                                ),
                                                            ),
                                                    ),
                                                    // Monzo issues a throwaway `anonuser_…` user id for every bank transfer, so
                                                    // the same person would otherwise become one counterparty account per
                                                    // transaction; such no-bank counterparties are matched by name instead.
                                                    rule(
                                                        RuleAction.IgnoreCounterpartyId,
                                                        conditions =
                                                            listOf(
                                                                Condition(
                                                                    "counterparty.user_id",
                                                                    ConditionOp.STARTS_WITH,
                                                                    value = "anonuser_",
                                                                ),
                                                            ),
                                                    ),
                                                    // A card payment abroad: Monzo settles in GBP and reports what the merchant
                                                    // was paid alongside, so the spend is booked in that currency.
                                                    rule(
                                                        RuleAction.Foreign(
                                                            ForeignAmount(
                                                                amount = ValueExpr(listOf("local_amount")),
                                                                currency = ValueExpr(listOf("local_currency")),
                                                            ),
                                                        ),
                                                    ),
                                                    // Foreign ATM withdrawals above the fee-free allowance carry a charge in
                                                    // `atm_fees_detailed.fee_amount` (integer minor units; null/0 otherwise).
                                                    // Import it as its own linked fee transfer. Monzo's `amount` is gross
                                                    // (= withdrawal_amount + fee_amount), so the fee is carved out of the main
                                                    // transfer rather than added on top.
                                                    fee(
                                                        FeeRule(
                                                            amount = ValueExpr(listOf("atm_fees_detailed.fee_amount")),
                                                            includedInAmount = true,
                                                        ),
                                                    ),
                                                ),
                                    ),
                            ),
                        ),
                    personExternalIdAttribute = "monzo-external-id",
                    tokenPageUrl = "https://developers.monzo.com/",
                    connectInstructions =
                        listOf(
                            "Open the Monzo Developer Playground in your browser.",
                            "Log in with your Monzo account credentials.",
                            "Monzo will send a magic link to your email or app. Approve the login.",
                            "Copy the access token shown on the playground page.",
                            "Paste the token below and save.",
                            "In the Monzo app, approve the API access notification so transactions can be read.",
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )

    /**
     * Monzo ATM-detection expressed declaratively (moved out of the import engine). Each rule routes
     * matching outgoing transactions to a single consolidated "ATM" counterparty account.
     */
    private val monzoAtmRules: List<StrategyRule> =
        listOf(
            listOf(Condition("atm_fees_detailed", ConditionOp.EXISTS)),
            listOf(Condition("labels", ConditionOp.ANY_ELEMENT_STARTS_WITH, value = "withdrawal.atm")),
            listOf(Condition("metadata.mcc", ConditionOp.EQUALS, value = "6011")),
            listOf(
                Condition("category", ConditionOp.EQUALS_IGNORE_CASE, value = "cash"),
                Condition("merchant", ConditionOp.EMPTY_OBJECT),
                Condition("counterparty", ConditionOp.EMPTY_OBJECT),
            ),
        ).map { conditions ->
            accounts(
                AccountSide.COUNTERPARTY,
                builtInNaming("id", "ATM"),
                conditions = listOf(Condition("amount", ConditionOp.NEGATIVE)) + conditions,
            )
        }
}
