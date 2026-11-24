package com.moneymanager.builtin

import com.moneymanager.domain.model.ApiImportStrategyId
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
import com.moneymanager.domain.model.apistrategy.ApiRequestSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiServerTimeSync
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.ApiTradeMappings
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.BodyFormat
import com.moneymanager.domain.model.apistrategy.FieldPlacement
import com.moneymanager.domain.model.apistrategy.InstrumentSplitMode
import com.moneymanager.domain.model.apistrategy.NonceFormat
import com.moneymanager.domain.model.apistrategy.NonceSpec
import com.moneymanager.domain.model.apistrategy.SecretEncoding
import com.moneymanager.domain.model.apistrategy.SigFieldLocation
import com.moneymanager.domain.model.apistrategy.SigPart
import com.moneymanager.domain.model.apistrategy.SignatureEncoding
import com.moneymanager.domain.model.apistrategy.SigningAlgorithm
import com.moneymanager.domain.model.apistrategy.TimestampFormat
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal object BybitApiStrategy {
    private val STRATEGY_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000d")

    /**
     * Built-in Bybit strategy — pure config over the generic signed-exchange engine (no provider code).
     * Bybit V5 signs `timestamp + apiKey + recvWindow + queryString` with HMAC-SHA256 (hex) and carries all
     * four values in `X-BAPI-*` headers; every list endpoint wraps its rows in `result.list`/`result.rows`
     * and pages within a date window by an opaque, already-URL-encoded `nextPageCursor`.
     *
     * The Funding and Unified Trading wallets are one "Bybit" account (moving between them changes
     * nothing), so wallet-to-wallet transfers aren't fetched. Flexible Savings principal sits in "Bybit Earn"
     * and derivatives profit/loss (realized PnL, funding, fees) flows through "Bybit Derivatives", leaving the
     * Bybit account comparable with the spot balances the exchange shows.
     *
     * Field paths follow the V5 docs; verify against a live response when connecting real keys (same
     * caveat as the other exchange built-ins) — in particular whether a withdrawal's `amount` excludes its
     * `withdrawFee`, and whether reinvested Earn yield belongs on "Bybit Earn" rather than the Bybit account.
     */
    fun strategy(now: Instant): ApiImportStrategy {
        val recvWindowMillis = "20000"

        // Every list endpoint pages a date window by nextPageCursor. Requests reaching further back than an
        // endpoint serves (trade and transaction-log history stops at 2 years) are skipped, not fatal.
        fun window(
            days: Int,
            limit: Int,
            lookbackDays: Int = ApiDateWindowing().lookbackDays,
        ) = ApiPaginationConfig(
            window =
                ApiDateWindowing(
                    startParam = "startTime",
                    endParam = "endTime",
                    windowDays = days,
                    lookbackDays = lookbackDays,
                    rangeErrorSubstrings = listOf("cannot exceed", "out of range", "range is too large", "time range too large"),
                ),
            paging = ApiPaging.Token(tokenField = "result.nextPageCursor", param = "cursor", urlEncoded = true),
            limitValue = limit,
            sendLimitParam = true,
        )

        // The asset endpoints require endTime - startTime < 30 days; the engine already ends every
        // non-final window 1 ms short of windowDays.
        val assetWindow = window(days = 30, limit = 50)
        // Trade, transaction-log and Earn history spans are capped at 7 days, and trade/transaction-log
        // history at 2 years back (720 keeps the grid-anchored first window inside that).
        val twoYears = 720
        val weekWindow = window(days = 7, limit = 100, lookbackDays = twoYears)

        fun signed(
            path: String,
            arrayKey: String,
            pagination: ApiPaginationConfig?,
            queryParams: List<ApiQueryParam> = emptyList(),
        ) = exchangeEndpoint(
            path,
            arrayKey,
            pagination,
            success = ResponseSuccess.CodeField("retCode", "0"),
            queryParams = queryParams,
        )

        fun status(value: String) = Condition("status", ConditionOp.EQUALS, value = value)

        // Longest-match-wins quote-asset suffixes for splitting a spot symbol ("BTCUSDT").
        val quoteAssets =
            listOf("BRL", "BRZ", "BTC", "DAI", "ETH", "EUR", "GBP", "MNT", "PLN", "TRY", "USDC", "USDE", "USDT")

        val earnAccount = "Bybit Earn"

        // Unified-account ledger rows that move money because of a derivatives position; the rest
        // (transfers in/out, spot fills) are already covered by the endpoints above.
        fun derivativesLedger(category: String) =
            ApiDataEndpoint(
                signed(
                    "v5/account/transaction-log",
                    "result.list",
                    window(days = 7, limit = 50, lookbackDays = twoYears),
                    queryParams = listOf(ApiQueryParam("accountType", "UNIFIED"), ApiQueryParam("category", category)),
                ),
                ApiEndpointKind.DEPOSITS,
                transactionMappings =
                    ApiTransactionMappings(
                        // change = cashFlow (realized PnL) + funding - fee: what the wallet actually moved.
                        amountField = "change",
                        descriptionField = "type",
                        timestampField = "transactionTime",
                        timestampFormat = TimestampFormat.EPOCH_MS,
                        amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                        rules =
                            onlyWhen(Condition("type", ConditionOp.IN, value = "TRADE,SETTLEMENT,DELIVERY,LIQUIDATION,ADL")) +
                                directionFromSign("change") +
                                counterparty("currency", "Bybit Derivatives"),
                    ),
            )

        return ApiImportStrategy(
            id = ApiImportStrategyId(STRATEGY_ID),
            name = "Bybit",
            config =
                ApiStrategyConfig(
                    baseUrl = "https://api.bybit.com",
                    requestSigning =
                        ApiRequestSigningConfig(
                            algorithm = SigningAlgorithm.HMAC_SHA256,
                            secretEncoding = SecretEncoding.UTF8,
                            signatureEncoding = SignatureEncoding.HEX,
                            message = listOf(SigPart.Nonce, SigPart.ApiKey, SigPart.Literal(recvWindowMillis), SigPart.QueryString),
                            apiKey = FieldPlacement(SigFieldLocation.HEADER, "X-BAPI-API-KEY"),
                            nonce = NonceSpec(NonceFormat.EPOCH_MS, FieldPlacement(SigFieldLocation.HEADER, "X-BAPI-TIMESTAMP")),
                            signature = FieldPlacement(SigFieldLocation.HEADER, "X-BAPI-SIGN"),
                            bodyFormat = BodyFormat.NONE,
                            // The default 5s window is easily spent by a slow request; the value is part of
                            // the signed message too, hence the Literal above.
                            staticHeaders = mapOf("X-BAPI-RECV-WINDOW" to recvWindowMillis),
                            // Bybit rejects a timestamp more than 1s ahead of its own clock, which no
                            // recv window forgives.
                            serverTimeSync = ApiServerTimeSync(path = "v5/market/time", field = "time"),
                        ),
                    accounts = ApiAccountsSource.Single(name = "Bybit", externalId = "bybit"),
                    dataEndpoints =
                        listOf(
                            ApiDataEndpoint(
                                signed(
                                    "v5/execution/list",
                                    "result.list",
                                    weekWindow,
                                    queryParams = listOf(ApiQueryParam("category", "spot")),
                                ),
                                ApiEndpointKind.TRADES,
                                tradeMappings =
                                    ApiTradeMappings(
                                        instrumentField = "symbol",
                                        splitMode = InstrumentSplitMode.QUOTE_SUFFIX,
                                        quoteAssets = quoteAssets,
                                        baseQuantityField = "execQty",
                                        quoteQuantityField = "execValue",
                                        timestampField = "execTime",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        idField = "execId",
                                        orderIdField = "orderId",
                                        rules =
                                            onlyWhen(Condition("execType", ConditionOp.EQUALS, value = "Trade")) +
                                                tradeSideFrom("side", "Buy") +
                                                fee(
                                                    FeeRule(
                                                        amount = ValueExpr(listOf("execFee")),
                                                        currency = ValueExpr(listOf("feeCurrency")),
                                                    ),
                                                ),
                                    ),
                            ),
                            ApiDataEndpoint(
                                signed("v5/asset/deposit/query-record", "result.rows", assetWindow),
                                ApiEndpointKind.DEPOSITS,
                                transactionMappings =
                                    ApiTransactionMappings(
                                        currencyField = "coin",
                                        timestampField = "successAt",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                                        counterpartyAddressField = "fromAddress",
                                        counterpartyNetworkField = "chain",
                                        txidField = "txID",
                                        // status 3 = success.
                                        rules = onlyWhen(status("3")) + counterparty("coin", "Bybit Funding"),
                                    ),
                            ),
                            ApiDataEndpoint(
                                signed("v5/asset/withdraw/query-record", "result.rows", assetWindow),
                                ApiEndpointKind.WITHDRAWALS,
                                transactionMappings =
                                    ApiTransactionMappings(
                                        currencyField = "coin",
                                        timestampField = "createTime",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                                        idField = "withdrawId",
                                        // An off-chain withdrawal to another Bybit user carries their UID here.
                                        counterpartyAddressField = "toAddress",
                                        counterpartyNetworkField = "chain",
                                        txidField = "txID",
                                        rules =
                                            onlyWhen(status("success")) +
                                                counterparty("coin", "Bybit Funding") +
                                                fee(FeeRule(amount = ValueExpr(listOf("withdrawFee")))),
                                    ),
                            ),
                            // Transfers from another Bybit user (by email/phone/UID) never appear on-chain.
                            ApiDataEndpoint(
                                signed("v5/asset/deposit/query-internal-record", "result.rows", assetWindow),
                                ApiEndpointKind.DEPOSITS,
                                transactionMappings =
                                    ApiTransactionMappings(
                                        currencyField = "coin",
                                        timestampField = "createdTime",
                                        timestampFormat = TimestampFormat.EPOCH_S,
                                        amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                                        counterpartyAddressField = "address",
                                        txidField = "txID",
                                        // status 2 = success.
                                        rules = onlyWhen(status("2")) + counterparty("coin", "Bybit Funding"),
                                    ),
                            ),
                            // Convert history has no time filter at all: one walk over 1-based "index" pages.
                            ApiDataEndpoint(
                                signed(
                                    "v5/asset/exchange/query-convert-history",
                                    "result.list",
                                    ApiPaginationConfig(
                                        paging = ApiPaging.Offset(param = "index", pageNumbers = true),
                                        // Bybit's maximum page of 100 is already the default limitValue.
                                        sendLimitParam = true,
                                    ),
                                ),
                                ApiEndpointKind.TRADES,
                                tradeMappings =
                                    ApiTradeMappings(
                                        splitMode = InstrumentSplitMode.EXPLICIT_FIELDS,
                                        baseAssetField = "toCoin",
                                        quoteAssetField = "fromCoin",
                                        baseQuantityField = "toAmount",
                                        quoteQuantityField = "fromAmount",
                                        timestampField = "createdAt",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        idField = "exchangeTxId",
                                        rules =
                                            onlyWhen(Condition("exchangeStatus", ConditionOp.EQUALS, value = "success")) +
                                                tradeSide(buy = true),
                                    ),
                            ),
                            // Stakes and redemptions share one list (there is no filter to split it), so the
                            // row's orderType decides the direction.
                            ApiDataEndpoint(
                                signed(
                                    "v5/earn/order",
                                    "result.list",
                                    weekWindow,
                                    queryParams = listOf(ApiQueryParam("category", "FlexibleSaving")),
                                ),
                                ApiEndpointKind.DEPOSITS,
                                transactionMappings =
                                    ApiTransactionMappings(
                                        amountField = "orderValue",
                                        currencyField = "coin",
                                        timestampField = "createdAt",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                                        descriptionField = "orderType",
                                        idField = "orderId",
                                        rules =
                                            onlyWhen(status("Success")) +
                                                incomingWhen(Condition("orderType", ConditionOp.IN, "Redeem")) +
                                                rule(
                                                    RuleAction.SetDirection(incoming = false),
                                                    conditions = listOf(Condition("orderType", ConditionOp.EXISTS)),
                                                ) +
                                                counterparty("coin", earnAccount),
                                    ),
                            ),
                            ApiDataEndpoint(
                                signed(
                                    "v5/earn/yield",
                                    "result.list",
                                    weekWindow,
                                    queryParams = listOf(ApiQueryParam("category", "FlexibleSaving")),
                                ),
                                ApiEndpointKind.DEPOSITS,
                                transactionMappings =
                                    ApiTransactionMappings(
                                        currencyField = "coin",
                                        timestampField = "createdAt",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                                        rules = onlyWhen(status("Success")) + counterparty("coin", "Bybit Earn Rewards"),
                                    ),
                            ),
                            derivativesLedger("linear"),
                            derivativesLedger("inverse"),
                        ),
                    // Bybit allows roughly 10 requests a second per endpoint group for a personal key.
                    rateLimitMillis = 250L,
                    rateLimitErrorSubstrings = listOf("Too many visits", "10006"),
                    tokenPageUrl = "https://www.bybit.com/app/user/api-management",
                    connectInstructions =
                        listOf(
                            "Open the Bybit API Management page in your browser and sign in.",
                            "Create a new key, choosing \"System-generated API Keys\" (HMAC) — self-generated RSA keys are not supported.",
                            "Choose \"Read-Only\" and tick the read permissions for Unified Trading (Spot, Contract), " +
                                "Assets (Wallet, Exchange) and Earn.",
                            "Copy the API key and paste it below as the API key.",
                            "Copy the API secret — shown only once — and paste it below as the API secret.",
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }
}
