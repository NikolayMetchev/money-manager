package com.moneymanager.builtin

import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiDateWindowing
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiPaging
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.ApiTokenExchange
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.WindowBoundFormat
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal object PayPalApiStrategy {
    private val STRATEGY_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000e")

    /**
     * The built-in PayPal API import strategy (Transaction Search). The credential is a REST app's client
     * id + secret, exchanged for a bearer token before every download. Each currency balance becomes its
     * own account; a balance carries nothing but its `currency`, so its stored id is prefixed to keep it
     * from matching another provider's account. Amounts are signed decimals with the fee (negative) beside
     * the gross amount, and history is fetched in PayPal's maximum 31-day windows.
     */
    fun strategy(now: Instant): ApiImportStrategy =
        ApiImportStrategy(
            id = ApiImportStrategyId(STRATEGY_ID),
            name = "PayPal API",
            config =
                ApiStrategyConfig(
                    baseUrl = "https://api-m.paypal.com",
                    tokenExchange = ApiTokenExchange(),
                    accounts =
                        ApiAccountsSource.Downloaded(
                            endpoint = ApiEndpointConfig(path = "/v1/reporting/balances", responseArrayKey = "balances"),
                            mappings =
                                ApiAccountMappings(
                                    idField = "currency",
                                    descriptionField = "currency",
                                    ownersArrayField = null,
                                    rules =
                                        listOf(
                                            rule(RuleAction.ExternalId(RuleValue.Template("paypal:{currency}"))),
                                            accounts(AccountSide.OWN, naming("currency", name = "PayPal {value}")),
                                        ),
                                ),
                        ),
                    dataEndpoints =
                        listOf(
                            ApiDataEndpoint(
                                endpoint =
                                    ApiEndpointConfig(
                                        path = "/v1/reporting/transactions",
                                        responseArrayKey = "transaction_details",
                                        queryParams =
                                            listOf(
                                                // The balance's raw id is its currency; passing it as `account.id`
                                                // is what ties each response back to its account.
                                                ApiQueryParam(name = "transaction_currency", dynamicSource = "account.id"),
                                                ApiQueryParam(name = "fields", value = "all"),
                                                ApiQueryParam(name = "balance_affecting_records_only", value = "Y"),
                                            ),
                                        pagination =
                                            ApiPaginationConfig(
                                                // Windows start on a 31-day boundary at or before the lookback, so
                                                // the lookback stays a window short of PayPal's three-year history.
                                                window =
                                                    ApiDateWindowing(
                                                        startParam = "start_date",
                                                        endParam = "end_date",
                                                        windowDays = 31,
                                                        lookbackDays = 1060,
                                                        boundFormat = WindowBoundFormat.ISO_8601,
                                                    ),
                                                paging =
                                                    ApiPaging.Offset(
                                                        param = "page",
                                                        pageNumbers = true,
                                                        totalCountField = "total_items",
                                                    ),
                                                limitParam = "page_size",
                                                limitValue = 500,
                                                sendLimitParam = true,
                                            ),
                                    ),
                                kind = ApiEndpointKind.BANK_TRANSACTIONS,
                                transactionMappings =
                                    ApiTransactionMappings(
                                        idField = "transaction_info.transaction_id",
                                        amountField = "transaction_info.transaction_amount.value",
                                        currencyField = "transaction_info.transaction_amount.currency_code",
                                        timestampField = "transaction_info.transaction_initiation_date",
                                        descriptionField = "transaction_info.transaction_subject",
                                        amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                                        rules =
                                            listOf(
                                                // Event codes T02xx are currency conversions: one item per currency, both
                                                // routed through one account so the pair nets out.
                                                accounts(
                                                    AccountSide.COUNTERPARTY,
                                                    builtInNaming("transaction_info.transaction_id", "PayPal Currency Conversion"),
                                                    conditions =
                                                        listOf(
                                                            Condition(
                                                                "transaction_info.transaction_event_code",
                                                                ConditionOp.STARTS_WITH,
                                                                value = "T02",
                                                            ),
                                                        ),
                                                ),
                                                accounts(AccountSide.COUNTERPARTY, naming("payer_info.payer_name.alternate_full_name")),
                                                declinedWhen(
                                                    Condition("transaction_info.transaction_status", ConditionOp.EQUALS, value = "D"),
                                                ),
                                                // The amount is gross; PayPal's fee (negative) comes off on top of it.
                                                fee(
                                                    FeeRule(
                                                        amount = ValueExpr.of("transaction_info.fee_amount.value"),
                                                        currency = ValueExpr.of("transaction_info.fee_amount.currency_code"),
                                                    ),
                                                ),
                                                attribute("transaction_info.transaction_event_code", "paypal-event-code"),
                                                attribute("payer_info.email_address", "paypal-payer-email"),
                                                // What the currency's balance holds once the item has happened.
                                                rule(
                                                    RuleAction.Balance(
                                                        amount = ValueExpr.of("transaction_info.ending_balance.value"),
                                                        currency = ValueExpr.of("transaction_info.ending_balance.currency_code"),
                                                    ),
                                                    name = "Statement balance",
                                                ),
                                            ),
                                    ),
                            ),
                        ),
                    tokenPageUrl = "https://developer.paypal.com/dashboard/applications/live",
                    connectInstructions =
                        listOf(
                            "Open the PayPal Developer Dashboard and log in with your PayPal account. Live REST apps need a " +
                                "PayPal business account (upgrading a personal account is free).",
                            "Under Apps & Credentials, switch to Live and create an app (type: Merchant).",
                            "In the app's features, tick Transaction Search and save. PayPal can take a few hours to grant it.",
                            "Copy the app's Client ID and Secret, paste them below and save.",
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
}
