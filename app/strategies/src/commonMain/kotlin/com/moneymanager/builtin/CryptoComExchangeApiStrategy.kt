package com.moneymanager.builtin

import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiAccountBridge
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiDateWindowing
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiInternalTransferReconcile
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiRequestSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.ApiTradeMappings
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.BodyFormat
import com.moneymanager.domain.model.apistrategy.FieldPlacement
import com.moneymanager.domain.model.apistrategy.HttpMethodType
import com.moneymanager.domain.model.apistrategy.NonceFormat
import com.moneymanager.domain.model.apistrategy.NonceSpec
import com.moneymanager.domain.model.apistrategy.ParamStringFormat
import com.moneymanager.domain.model.apistrategy.RequestIdSpec
import com.moneymanager.domain.model.apistrategy.SigFieldLocation
import com.moneymanager.domain.model.apistrategy.SigPart
import com.moneymanager.domain.model.apistrategy.SigningAlgorithm
import com.moneymanager.domain.model.apistrategy.TimestampFormat
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal object CryptoComExchangeApiStrategy {
    private val STRATEGY_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000009")

    /**
     * Built-in Crypto.com **Exchange** API strategy — pure config over the generic signed-exchange
     * engine (no provider code). Uses HMAC-SHA256 request signing, a single "Crypto.com Exchange"
     * account holding all assets, and data endpoints for trades, order history and fiat/crypto
     * deposits/withdrawals. Internal transfers reconcile against the CSV "Crypto.com" App account.
     *
     * Field paths follow the Crypto.com Exchange v1 REST docs; verify against a live response when
     * connecting real keys (the shapes are covered by the db-level E2E test).
     */
    fun strategy(now: Instant): ApiImportStrategy {
        // get-deposit-history / get-withdrawal-history serve years of history, so page a long lookback.
        val historyWindow =
            ApiPaginationConfig(window = ApiDateWindowing(startParam = "start_ts", endParam = "end_ts", windowDays = 90))
        // get-trades / get-order-history only serve the last 6 months (older trades come from the CSV
        // import) and cap the window at 7 days; unlike the deposit/withdrawal endpoints they take
        // start_time/end_time (start_ts/end_ts is silently ignored and defaults to the last 24h).
        val recentWindow =
            ApiPaginationConfig(
                window = ApiDateWindowing(startParam = "start_time", endParam = "end_time", windowDays = 7, lookbackDays = 180),
            )

        fun signed(
            path: String,
            key: String,
            pagination: ApiPaginationConfig,
        ) = exchangeEndpoint(
            path,
            key,
            pagination,
            success = ResponseSuccess.CodeField("code", "0"),
            method = HttpMethodType.POST,
        )
        val tradeMappings =
            ApiTradeMappings(
                instrumentField = "instrument_name",
                baseQuantityField = "traded_quantity",
                priceField = "traded_price",
                timestampField = "create_time",
                timestampFormat = TimestampFormat.EPOCH_MS,
                idField = "trade_id",
                orderIdField = "order_id",
                rules =
                    tradeSideFrom("side", "BUY,buy") +
                        fee(FeeRule(amount = ValueExpr(listOf("fees")), currency = ValueExpr(listOf("fee_instrument_name")))),
            )
        val orderMappings =
            ApiTradeMappings(
                instrumentField = "instrument_name",
                baseQuantityField = "quantity",
                timestampField = "create_time",
                timestampFormat = TimestampFormat.EPOCH_MS,
                idField = "order_id",
                orderIdField = "order_id",
                // The live get-order-history payload calls this "order_type" (not "type").
                orderTypeField = "order_type",
                orderStatusField = "status",
                limitPriceField = "limit_price",
                avgPriceField = "avg_price",
                updateTimestampField = "update_time",
                clientOidField = "client_oid",
                timeInForceField = "time_in_force",
                rules = tradeSideFrom("side", "BUY,buy"),
            )

        fun transferMappings(addressField: String) =
            ApiTransactionMappings(
                timestampField = "create_time",
                timestampFormat = TimestampFormat.EPOCH_MS,
                amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                // Model the blockchain wallet as a per-address account; key the movement by its on-chain
                // txid so it reconciles with the same transaction seen from another source. A deposit's
                // counterparty is the sender (source_address); a withdrawal's is the destination (address).
                counterpartyAddressField = addressField,
                counterpartyNetworkField = "network_id",
                txidField = "txid",
                // An internal transfer (funds moved from/to the Crypto.com App) names no wallet: its address
                // is cleared and it is booked directly against the "Crypto.com" App account, so the same
                // movement in the CSV export reconciles to it.
                rules =
                    listOf(
                        rule(
                            RuleAction.SetValue(addressField, RuleValue.constant("")),
                            RuleAction.Account(AccountSide.COUNTERPARTY, fixedNaming("currency", "Crypto.com")),
                            conditions = listOf(Condition("address", ConditionOp.IN, "INTERNAL_DEPOSIT,INTERNAL_WITHDRAWAL")),
                        ),
                    ),
            )
        return ApiImportStrategy(
            id = ApiImportStrategyId(STRATEGY_ID),
            name = "Crypto.com Exchange",
            config =
                ApiStrategyConfig(
                    baseUrl = "https://api.crypto.com/exchange/v1",
                    requestSigning =
                        ApiRequestSigningConfig(
                            algorithm = SigningAlgorithm.HMAC_SHA256,
                            message =
                                listOf(
                                    SigPart.Method,
                                    SigPart.RequestId,
                                    SigPart.ApiKey,
                                    SigPart.ParamString(ParamStringFormat.SORTED_CONCAT),
                                    SigPart.Nonce,
                                ),
                            apiKey = FieldPlacement(SigFieldLocation.BODY_FIELD, "api_key"),
                            nonce = NonceSpec(NonceFormat.EPOCH_MS, FieldPlacement(SigFieldLocation.BODY_FIELD, "nonce")),
                            signature = FieldPlacement(SigFieldLocation.BODY_FIELD, "sig"),
                            requestId = RequestIdSpec(placement = FieldPlacement(SigFieldLocation.BODY_FIELD, "id")),
                            method = FieldPlacement(SigFieldLocation.BODY_FIELD, "method"),
                            bodyFormat = BodyFormat.JSON_ENVELOPE,
                            paramsEnvelopeKey = "params",
                        ),
                    accounts = ApiAccountsSource.Single(name = "Crypto.com Exchange", externalId = "crypto-com-exchange"),
                    dataEndpoints =
                        listOf(
                            ApiDataEndpoint(
                                signed("private/get-trades", "result.data", recentWindow),
                                ApiEndpointKind.TRADES,
                                tradeMappings = tradeMappings,
                            ),
                            ApiDataEndpoint(
                                signed("private/get-order-history", "result.data", recentWindow),
                                ApiEndpointKind.ORDERS,
                                tradeMappings = orderMappings,
                            ),
                            ApiDataEndpoint(
                                signed("private/get-deposit-history", "result.deposit_list", historyWindow),
                                ApiEndpointKind.DEPOSITS,
                                transactionMappings = transferMappings("source_address"),
                            ),
                            ApiDataEndpoint(
                                signed("private/get-withdrawal-history", "result.withdrawal_list", historyWindow),
                                ApiEndpointKind.WITHDRAWALS,
                                transactionMappings = transferMappings("address"),
                            ),
                        ),
                    internalTransferReconcile =
                        ApiInternalTransferReconcile(
                            bridges = listOf(ApiAccountBridge(otherAccountName = "Crypto.com")),
                            window = SettlementWindow.Elapsed(24 * 3600),
                            amountTolerancePercent = "2",
                        ),
                    tokenPageUrl = "https://exchange.crypto.com/settings/api-management",
                    connectInstructions =
                        listOf(
                            "Open the Crypto.com Exchange API management page in your browser and sign in.",
                            "Create a new API key with read-only permissions (do not grant withdrawal or " +
                                "trading permissions).",
                            "Copy the API key and paste it below as the API key.",
                            "Copy the Secret Key and paste it below as the API secret.",
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }
}
