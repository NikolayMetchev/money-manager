package com.moneymanager.builtin

import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiDateWindowing
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiFanOut
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiPaging
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiRequestSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiServerTimeSync
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.ApiTradeMappings
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.ApiValueSet
import com.moneymanager.domain.model.apistrategy.BodyFormat
import com.moneymanager.domain.model.apistrategy.FieldPlacement
import com.moneymanager.domain.model.apistrategy.HttpMethodType
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
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal object BinanceApiStrategy {
    private val STRATEGY_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000b")

    /**
     * Built-in Binance API strategy — pure config over the generic signed-exchange engine (no provider
     * code). Binance's REST auth is HMAC-SHA256 over the query string + body, api key in the
     * `X-MBX-APIKEY` header, `timestamp` and `signature` appended to the query — the
     * [ApiRequestSigningConfig] KDoc names this shape explicitly, and `ApiRequestSignerTest` (`utils:rest`)
     * carries Binance's own published signature vector.
     *
     * Binance has no account-wide trade feed (`myTrades` requires a `symbol`), so spot trades are
     * fetched via [ApiEndpointConfig.fanOut]: candidate symbols are the cross product of every asset
     * seen this session (current balances, plus assets seen in deposits/withdrawals/convert/fiat
     * payments, so a fully-disposed-of asset is still swept) with a static list of quote assets,
     * intersected against `exchangeInfo`'s real symbol universe so a nonexistent pair is never
     * requested. `myTrades` also caps `startTime`/`endTime` to 24h apart, so it pages by ascending trade
     * id instead ([ApiPaging.ForwardId]) rather than by date window.
     *
     * Field paths follow the Binance REST docs; verify against a live response when connecting real
     * keys (same caveat as the Kraken/Crypto.com built-ins).
     */
    fun strategy(now: Instant): ApiImportStrategy {
        // Deposit/withdrawal history: a date window further paged by "offset"/"limit" (max 1000/page).
        val cryptoHistoryWindow =
            ApiPaginationConfig(
                window = ApiDateWindowing(startParam = "startTime", endParam = "endTime", windowDays = 90),
                paging = ApiPaging.Offset(param = "offset"),
                limitValue = 1_000,
                sendLimitParam = true,
            )

        // Fiat orders/payments: a date window further paged by 1-based "page"/"rows" (max 500/page),
        // bounded by the envelope's top-level "total".
        val fiatHistoryWindow =
            ApiPaginationConfig(
                window = ApiDateWindowing(startParam = "beginTime", endParam = "endTime", windowDays = 90),
                paging = ApiPaging.Offset(param = "page", pageNumbers = true, totalCountField = "total"),
                limitParam = "rows",
                limitValue = 500,
                sendLimitParam = true,
            )

        // Convert's tradeFlow enforces a hard 30-day max between startTime/endTime.
        val convertWindow =
            ApiPaginationConfig(window = ApiDateWindowing(startParam = "startTime", endParam = "endTime", windowDays = 30))

        // Simple Earn history: a date window (Binance's subscription/redemption/rewards history endpoints
        // reject a startTime/endTime span longer than 30 days with "-6021 Query time range too large")
        // further paged by 1-based "current"/"size" (max 100/page), bounded by the envelope's top-level "total".
        val earnHistoryWindow =
            ApiPaginationConfig(
                window = ApiDateWindowing(startParam = "startTime", endParam = "endTime", windowDays = 30),
                paging = ApiPaging.Offset(param = "current", pageNumbers = true, totalCountField = "total"),
                // "size" caps at 100 a page, which is already ApiPaginationConfig's default limitValue.
                limitParam = "size",
                sendLimitParam = true,
            )

        // asset/transfer "Support query within the last 6 months only" - a startTime older than that is
        // rejected with "-5026 Start time query records range is too large" (the message is about how far
        // back startTime reaches, not the startTime/endTime span), so its sweep is capped at 6 months
        // rather than the default multi-year lookback. dateWindows anchors the first window's start down
        // to a windowDays-grid boundary, so it can reach ~windowDays before lookbackDays - 135 + 30 keeps
        // the earliest startTime the engine ever sends comfortably inside 6 months.
        val universalTransferWindow = earnHistoryWindow.copy(window = earnHistoryWindow.window?.copy(lookbackDays = 135))

        // dribblet has no page/offset scheme at all, which is exactly what [nestedItemsKey] requires (a
        // flattened page's item count no longer matches the page size an offset loop compares against).
        val dustWindow =
            ApiPaginationConfig(window = ApiDateWindowing(startParam = "startTime", endParam = "endTime", windowDays = 30))

        // myTrades needs a symbol (see fan-out below) and caps startTime/endTime to 24h, so it walks
        // forward by trade id instead of by date window.
        val spotTradeCursor =
            ApiPaginationConfig(
                paging = ApiPaging.ForwardId(param = "fromId", idField = "id"),
                limitValue = 1_000,
                sendLimitParam = true,
            )

        fun signed(
            path: String,
            key: String,
            pagination: ApiPaginationConfig?,
            method: HttpMethodType = HttpMethodType.GET,
            queryParams: List<ApiQueryParam> = emptyList(),
            requestCostWeight: Int = 1,
        ) = ApiEndpointConfig(
            path = path,
            responseArrayKey = key,
            queryParams = queryParams,
            method = method,
            pagination = pagination,
            requestCostWeight = requestCostWeight,
        )

        // Fiat-envelope endpoints (fiat/orders, fiat/payments) report success via "code" == "000000",
        // never an HTTP-status-only or error-array shape.
        fun signedFiat(
            path: String,
            transactionType: String,
            requestCostWeight: Int = 1,
        ) = ApiEndpointConfig(
            path = path,
            responseArrayKey = "data",
            queryParams = listOf(ApiQueryParam(name = "transactionType", value = transactionType)),
            pagination = fiatHistoryWindow,
            successCodeField = "code",
            successCodeOkValue = "000000",
            requestCostWeight = requestCostWeight,
        )

        // Longest-match-wins quote-asset suffixes for QUOTE_SUFFIX symbol splitting (spot trades) and,
        // doubled as the fan-out cross product's other side, for candidate symbol generation.
        val quoteAssets =
            listOf(
                "USDT",
                "FDUSD",
                "USDC",
                "BUSD",
                "TUSD",
                "DAI",
                "BTC",
                "ETH",
                "BNB",
                "EUR",
                "GBP",
                "TRY",
                "BRL",
                "AUD",
                "RUB",
                "UAH",
                "ZAR",
                "PLN",
                "RON",
                "ARS",
                "NGN",
            )

        val depositsEndpoint =
            signed(
                "sapi/v1/capital/deposit/hisrec",
                "",
                cryptoHistoryWindow,
            )
        val withdrawalsEndpoint =
            signed(
                "sapi/v1/capital/withdraw/history",
                "",
                cryptoHistoryWindow,
            )
        // tradeFlow returns "moreData": true when a single 30-day window holds more than [limitValue]
        // conversions - the engine has no continuation scheme for that flag (unlike offset/cursor
        // paging), so a window with more than 1000 conversions silently truncates. Requesting the
        // maximum page size makes that essentially never happen for a personal account.
        val convertEndpoint =
            signed(
                "sapi/v1/convert/tradeFlow",
                "list",
                convertWindow,
                queryParams = listOf(ApiQueryParam(name = "limit", value = "1000")),
                requestCostWeight = 20,
            )
        val fiatBuyEndpoint = signedFiat("sapi/v1/fiat/payments", transactionType = "0")
        val fiatSellEndpoint = signedFiat("sapi/v1/fiat/payments", transactionType = "1")

        // Mirrors ExchangeApiImportService.endpointDedupeKey (path + sorted static query params) so
        // ApiValueSet.From*Endpoint references below match how the engine keys its per-session item
        // maps - this module can't depend on app:apiimporter (config-only, no engine coupling), so the
        // tiny computation is duplicated here rather than shared.
        fun dedupeKey(endpoint: ApiEndpointConfig): String {
            val staticParams =
                endpoint.queryParams
                    .filter { it.value != null }
                    .sortedBy { it.name }
                    .joinToString("&") { "${it.name}=${it.value}" }
            return if (staticParams.isEmpty()) endpoint.path else "${endpoint.path}?$staticParams"
        }

        // Binance's Earn and Funding wallets are separate balances the spot-only getUserAsset never
        // reports; each gets its own counterparty account so the "Binance" account stays spot-shaped.
        val binanceEarnAccount = "Binance Earn"
        val binanceFundingWalletAccount = "Binance Funding Wallet"

        val universalTransferMappings =
            ApiTransactionMappings(
                currencyField = "asset",
                timestampField = "timestamp",
                timestampFormat = TimestampFormat.EPOCH_MS,
                amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                idField = "tranId",
                rules =
                    onlyWhen(Condition("status", ConditionOp.EQUALS, value = "CONFIRMED")) +
                        counterparty("asset", binanceFundingWalletAccount),
            )

        // Simple Earn history costs 150 request-weight a call against Binance's ~6000/min budget, so it is
        // paced several times slower than the weight-1 endpoints (see requestCostWeight).
        val earnRequestCostWeight = 5
        val earnSubscriptionsEndpoint =
            signed(
                "sapi/v1/simple-earn/flexible/history/subscriptionRecord",
                "rows",
                earnHistoryWindow,
                requestCostWeight = earnRequestCostWeight,
            )
        val earnRedemptionsEndpoint =
            signed(
                "sapi/v1/simple-earn/flexible/history/redemptionRecord",
                "rows",
                earnHistoryWindow,
                requestCostWeight = earnRequestCostWeight,
            )

        // rewardsRecord requires a "type", so each kind of interest is its own endpoint sharing one path -
        // told apart by the static query param, exactly like the two fiat/orders endpoints.
        fun earnRewardsEndpoint(type: String) =
            signed(
                "sapi/v1/simple-earn/flexible/history/rewardsRecord",
                "rows",
                earnHistoryWindow,
                queryParams = listOf(ApiQueryParam(name = "type", value = type)),
                requestCostWeight = earnRequestCostWeight,
            )
        val earnRewardTypes = listOf("BONUS", "REALTIME", "REWARDS")

        // One conversion sweeps several small balances into BNB at once, so the rows that carry the money
        // are the nested per-asset details, not the conversions themselves.
        val dustEndpoint =
            signed("sapi/v1/asset/dribblet", "userAssetDribblets", dustWindow)
                .copy(nestedItemsKey = "userAssetDribbletDetails")

        // assetDividend pages only by "limit" (max 500) within a window - like convert/tradeFlow it has no
        // continuation scheme the engine can drive, so ask for the maximum page and accept that a single
        // window holding more than 500 distributions would truncate (never true for a personal account).
        // assetDividend itself allows a 180-day span; it just inherits the stricter Simple Earn window.
        val dividendEndpoint =
            signed(
                "sapi/v1/asset/assetDividend",
                "rows",
                ApiPaginationConfig(window = earnHistoryWindow.window, limitValue = 500),
                queryParams = listOf(ApiQueryParam(name = "limit", value = "500")),
            )

        // Universal transfers between the Spot and Funding wallets; "type" is required, so the two
        // directions are two endpoints sharing a path.
        fun universalTransferEndpoint(type: String) =
            signed(
                "sapi/v1/asset/transfer",
                "rows",
                universalTransferWindow,
                queryParams = listOf(ApiQueryParam(name = "type", value = type)),
            )
        val spotToFundingEndpoint = universalTransferEndpoint("MAIN_FUNDING")
        val fundingToSpotEndpoint = universalTransferEndpoint("FUNDING_MAIN")

        // Every asset the account is known to have touched - the left side of the spot-symbol cross
        // product. An asset only ever held inside Earn, swept as dust or received as a distribution has no
        // balance and no deposit, so those endpoints are the only places its symbol ever appears.
        val myTradesAssetSources =
            listOf(
                ApiValueSet.FromValueEndpoint("sapi/v3/asset/getUserAsset", listOf("asset")),
                ApiValueSet.FromDataEndpoint(depositsEndpoint.path, listOf("coin")),
                ApiValueSet.FromDataEndpoint(withdrawalsEndpoint.path, listOf("coin")),
                ApiValueSet.FromDataEndpoint(convertEndpoint.path, listOf("fromAsset", "toAsset")),
                ApiValueSet.FromDataEndpoint(dedupeKey(fiatBuyEndpoint), listOf("cryptoCurrency")),
                ApiValueSet.FromDataEndpoint(dedupeKey(fiatSellEndpoint), listOf("cryptoCurrency")),
                ApiValueSet.FromDataEndpoint(earnSubscriptionsEndpoint.path, listOf("asset")),
                ApiValueSet.FromDataEndpoint(earnRedemptionsEndpoint.path, listOf("asset")),
                ApiValueSet.FromDataEndpoint(dividendEndpoint.path, listOf("asset")),
                ApiValueSet.FromDataEndpoint(dustEndpoint.path, listOf("fromAsset")),
                ApiValueSet.FromDataEndpoint(dedupeKey(spotToFundingEndpoint), listOf("asset")),
                ApiValueSet.FromDataEndpoint(dedupeKey(fundingToSpotEndpoint), listOf("asset")),
            ) +
                earnRewardTypes.map { type ->
                    ApiValueSet.FromDataEndpoint(dedupeKey(earnRewardsEndpoint(type)), listOf("asset"))
                }

        val myTradesEndpoint =
            signed(
                "api/v3/myTrades",
                "",
                spotTradeCursor,
                requestCostWeight = 2,
            ).copy(
                fanOut =
                    ApiFanOut(
                        param = "symbol",
                        values =
                            ApiValueSet.CrossProduct(
                                left = ApiValueSet.Union(myTradesAssetSources),
                                right = ApiValueSet.Static(quoteAssets),
                            ),
                        // exchangeInfo's responseArrayKey ("symbols") already unwraps the response to individual
                        // symbol objects before they're stored, so each item's own "symbol" field is read
                        // directly here - not "symbols.symbol".
                        validAgainst = ApiValueSet.FromValueEndpoint("api/v3/exchangeInfo", listOf("symbol")),
                    ),
            )

        return ApiImportStrategy(
            id = ApiImportStrategyId(STRATEGY_ID),
            name = "Binance",
            config =
                ApiStrategyConfig(
                    baseUrl = "https://api.binance.com",
                    requestSigning =
                        ApiRequestSigningConfig(
                            algorithm = SigningAlgorithm.HMAC_SHA256,
                            secretEncoding = SecretEncoding.UTF8,
                            signatureEncoding = SignatureEncoding.HEX,
                            message = listOf(SigPart.QueryString, SigPart.Body),
                            apiKey = FieldPlacement(SigFieldLocation.HEADER, "X-MBX-APIKEY"),
                            nonce = NonceSpec(NonceFormat.EPOCH_MS, FieldPlacement(SigFieldLocation.QUERY, "timestamp")),
                            signature = FieldPlacement(SigFieldLocation.QUERY, "signature"),
                            bodyFormat = BodyFormat.QUERY_ONLY,
                            // Binance rejects a request whose timestamp is older than recvWindow with
                            // "-1021 Timestamp for this request is outside of the recvWindow". The default
                            // is only 5s, which a full first download can exceed on a slow sapi/* call.
                            // 60s is Binance's documented maximum.
                            signedParams = listOf(ApiQueryParam("recvWindow", "60000")),
                            // recvWindow only forgives a timestamp that is too OLD - Binance still rejects
                            // one more than 1s in its future - so a locally-fast clock needs the offset,
                            // not a wider window. Measured once per download against Binance's own clock.
                            serverTimeSync = ApiServerTimeSync(path = "api/v3/time", field = "serverTime"),
                        ),
                    accounts = ApiAccountsSource.Single(name = "Binance", externalId = "binance"),
                    valueEndpoints =
                        listOf(
                            signed("sapi/v3/asset/getUserAsset", "", pagination = null, method = HttpMethodType.POST),
                            // Public, unsigned and never persisted (~2MB of symbol metadata unrelated to
                            // anything importable) — used only to validate fan-out candidate symbols.
                            ApiEndpointConfig(
                                path = "api/v3/exchangeInfo",
                                responseArrayKey = "symbols",
                                unsigned = true,
                                storeResponse = false,
                            ),
                        ),
                    dataEndpoints =
                        listOf(
                            ApiDataEndpoint(
                                depositsEndpoint,
                                ApiEndpointKind.DEPOSITS,
                                transactionMappings =
                                    ApiTransactionMappings(
                                        currencyField = "coin",
                                        timestampField = "insertTime",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                                        counterpartyAddressField = "address",
                                        counterpartyNetworkField = "network",
                                        txidField = "txId",
                                        // status: 0=pending, 1=success, 2=rejected, 6=credited but cannot
                                        // withdraw, 7=wrong deposit, 8=waiting user confirm.
                                        rules =
                                            onlyWhen(Condition("status", ConditionOp.EQUALS, value = "1")) +
                                                counterparty("coin", "Binance Funding"),
                                    ),
                            ),
                            ApiDataEndpoint(
                                withdrawalsEndpoint,
                                ApiEndpointKind.WITHDRAWALS,
                                transactionMappings =
                                    ApiTransactionMappings(
                                        currencyField = "coin",
                                        timestampField = "applyTime",
                                        timestampFormat = TimestampFormat.PATTERN,
                                        timestampPattern = "yyyy-MM-dd HH:mm:ss",
                                        amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                                        counterpartyAddressField = "address",
                                        counterpartyNetworkField = "network",
                                        txidField = "txId",
                                        // status 6 = completed (see the capital/withdraw/history docs).
                                        rules =
                                            onlyWhen(Condition("status", ConditionOp.EQUALS, value = "6")) +
                                                counterparty("coin", "Binance Funding") +
                                                // "amount" is net of the fee - transactionFee is booked separately.
                                                fee(FeeRule(amount = ValueExpr(listOf("transactionFee")))),
                                    ),
                            ),
                            ApiDataEndpoint(
                                signedFiat("sapi/v1/fiat/orders", transactionType = "0"),
                                ApiEndpointKind.DEPOSITS,
                                transactionMappings =
                                    ApiTransactionMappings(
                                        currencyField = "fiatCurrency",
                                        timestampField = "createTime",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                                        idField = "orderNo",
                                        rules =
                                            onlyWhen(Condition("status", ConditionOp.EQUALS, value = "Successful")) +
                                                counterparty("fiatCurrency", "Binance Bank"),
                                    ),
                            ),
                            ApiDataEndpoint(
                                signedFiat("sapi/v1/fiat/orders", transactionType = "1"),
                                ApiEndpointKind.WITHDRAWALS,
                                transactionMappings =
                                    ApiTransactionMappings(
                                        currencyField = "fiatCurrency",
                                        timestampField = "createTime",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                                        idField = "orderNo",
                                        // `amount` is what left the account, NET of the charge: without this
                                        // the withdrawal is under-reported by the fee, and the statement
                                        // export - which records the gross - can never be reconciled against
                                        // it. The deposit endpoint above needs no equivalent: Binance
                                        // returns totalFee "0" on every fiat deposit.
                                        rules =
                                            onlyWhen(Condition("status", ConditionOp.EQUALS, value = "Successful")) +
                                                counterparty("fiatCurrency", "Binance Bank") +
                                                fee(FeeRule(amount = ValueExpr(listOf("totalFee")))),
                                    ),
                            ),
                            ApiDataEndpoint(
                                fiatBuyEndpoint,
                                ApiEndpointKind.TRADES,
                                tradeMappings =
                                    ApiTradeMappings(
                                        splitMode = InstrumentSplitMode.EXPLICIT_FIELDS,
                                        baseAssetField = "cryptoCurrency",
                                        quoteAssetField = "fiatCurrency",
                                        // Buying crypto with fiat: obtainAmount = crypto received (base),
                                        // sourceAmount = fiat spent (quote).
                                        baseQuantityField = "obtainAmount",
                                        quoteQuantityField = "sourceAmount",
                                        timestampField = "createTime",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        idField = "orderNo",
                                        rules =
                                            onlyWhen(Condition("status", ConditionOp.EQUALS, value = "Completed")) +
                                                tradeSide(buy = true) +
                                                fee(
                                                    FeeRule(
                                                        amount = ValueExpr(listOf("totalFee")),
                                                        currency = ValueExpr(listOf("fiatCurrency")),
                                                    ),
                                                ),
                                    ),
                            ),
                            ApiDataEndpoint(
                                fiatSellEndpoint,
                                ApiEndpointKind.TRADES,
                                tradeMappings =
                                    ApiTradeMappings(
                                        splitMode = InstrumentSplitMode.EXPLICIT_FIELDS,
                                        baseAssetField = "cryptoCurrency",
                                        quoteAssetField = "fiatCurrency",
                                        // Selling crypto for fiat: sourceAmount = crypto given (base),
                                        // obtainAmount = fiat received (quote).
                                        baseQuantityField = "sourceAmount",
                                        quoteQuantityField = "obtainAmount",
                                        timestampField = "createTime",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        idField = "orderNo",
                                        rules =
                                            onlyWhen(Condition("status", ConditionOp.EQUALS, value = "Completed")) +
                                                tradeSide(buy = false) +
                                                fee(
                                                    FeeRule(
                                                        amount = ValueExpr(listOf("totalFee")),
                                                        currency = ValueExpr(listOf("fiatCurrency")),
                                                    ),
                                                ),
                                    ),
                            ),
                            ApiDataEndpoint(
                                convertEndpoint,
                                ApiEndpointKind.TRADES,
                                tradeMappings =
                                    ApiTradeMappings(
                                        splitMode = InstrumentSplitMode.EXPLICIT_FIELDS,
                                        baseAssetField = "toAsset",
                                        quoteAssetField = "fromAsset",
                                        baseQuantityField = "toAmount",
                                        quoteQuantityField = "fromAmount",
                                        timestampField = "createTime",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        idField = "orderId",
                                        rules =
                                            onlyWhen(Condition("orderStatus", ConditionOp.EQUALS, value = "SUCCESS")) +
                                                tradeSide(buy = true),
                                    ),
                            ),
                            // Simple Earn moves principal between the Spot wallet and the Earn wallet.
                            // Booking it against a named "Binance Earn" account (rather than netting it
                            // away) keeps the Binance account comparable with the spot-only balances
                            // getUserAsset reports, and leaves the staked principal visible.
                            ApiDataEndpoint(
                                earnSubscriptionsEndpoint,
                                ApiEndpointKind.WITHDRAWALS,
                                transactionMappings =
                                    ApiTransactionMappings(
                                        currencyField = "asset",
                                        timestampField = "time",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                                        idField = "purchaseId",
                                        // A subscription can be funded partly from the Funding wallet
                                        // (amtFromSpot/amtFromFunding), but both wallets are the one
                                        // Binance account here, so the whole amount moves as one leg.
                                        rules =
                                            onlyWhen(Condition("status", ConditionOp.EQUALS, value = "SUCCESS")) +
                                                counterparty("asset", binanceEarnAccount),
                                    ),
                            ),
                            ApiDataEndpoint(
                                earnRedemptionsEndpoint,
                                ApiEndpointKind.DEPOSITS,
                                transactionMappings =
                                    ApiTransactionMappings(
                                        currencyField = "asset",
                                        timestampField = "time",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                                        idField = "redeemId",
                                        rules =
                                            onlyWhen(Condition("status", ConditionOp.EQUALS, value = "PAID")) +
                                                counterparty("asset", binanceEarnAccount),
                                    ),
                            ),
                            ApiDataEndpoint(
                                spotToFundingEndpoint,
                                ApiEndpointKind.WITHDRAWALS,
                                transactionMappings = universalTransferMappings,
                            ),
                            ApiDataEndpoint(
                                fundingToSpotEndpoint,
                                ApiEndpointKind.DEPOSITS,
                                transactionMappings = universalTransferMappings,
                            ),
                            ApiDataEndpoint(
                                dividendEndpoint,
                                ApiEndpointKind.DEPOSITS,
                                transactionMappings =
                                    ApiTransactionMappings(
                                        currencyField = "asset",
                                        timestampField = "divTime",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                                        descriptionField = "enInfo",
                                        idField = "tranId",
                                        rules = listOf(counterparty("asset", "Binance Distribution")),
                                    ),
                            ),
                            // A dust conversion's detail rows are what actually moved: each swaps one small
                            // balance for BNB. "transferedAmount" is already net of "serviceChargeAmount", so
                            // the charge is deliberately not mapped as a fee - booking it as its own movement
                            // would take the same BNB out twice.
                            ApiDataEndpoint(
                                dustEndpoint,
                                ApiEndpointKind.TRADES,
                                tradeMappings =
                                    ApiTradeMappings(
                                        splitMode = InstrumentSplitMode.EXPLICIT_FIELDS,
                                        baseAssetField = "toAsset",
                                        quoteAssetField = "fromAsset",
                                        baseQuantityField = "transferedAmount",
                                        quoteQuantityField = "amount",
                                        timestampField = "operateTime",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        idField = "transId",
                                        rules =
                                            listOf(
                                                // The rows never name the asset they acquired: dust always converts to BNB.
                                                rule(RuleAction.SetValue("toAsset", RuleValue.constant("BNB"))),
                                                tradeSide(buy = true),
                                            ),
                                    ),
                            ),
                            ApiDataEndpoint(
                                myTradesEndpoint,
                                ApiEndpointKind.TRADES,
                                tradeMappings =
                                    ApiTradeMappings(
                                        instrumentField = "symbol",
                                        splitMode = InstrumentSplitMode.QUOTE_SUFFIX,
                                        quoteAssets = quoteAssets,
                                        baseQuantityField = "qty",
                                        quoteQuantityField = "quoteQty",
                                        timestampField = "time",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        idField = "id",
                                        orderIdField = "orderId",
                                        rules =
                                            tradeSideFrom("isBuyer", "true") +
                                                fee(
                                                    FeeRule(
                                                        amount = ValueExpr(listOf("commission")),
                                                        currency = ValueExpr(listOf("commissionAsset")),
                                                    ),
                                                ) +
                                                // Binance's numeric trade "id" is scoped per symbol, not global —
                                                // two different pairs can report the same id, so the composite key
                                                // (symbol + id) is what's actually unique.
                                                compositeId("symbol", "id"),
                                    ),
                            ),
                        ) +
                            // Interest is real income the ledger never sees otherwise; its own counterparty
                            // account makes lifetime interest readable on its own. Reward rows carry no id
                            // of any kind, so the dedupe key has to be composed from the row's own fields.
                            earnRewardTypes.map { type ->
                                ApiDataEndpoint(
                                    earnRewardsEndpoint(type),
                                    ApiEndpointKind.DEPOSITS,
                                    transactionMappings =
                                        ApiTransactionMappings(
                                            amountField = "rewards",
                                            currencyField = "asset",
                                            timestampField = "time",
                                            timestampFormat = TimestampFormat.EPOCH_MS,
                                            amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                                            // "type" is part of the key because the three reward endpoints
                                            // are otherwise indistinguishable: a BONUS and a REALTIME row
                                            // for the same project, second and amount would collide and
                                            // one of the two would be dropped as a duplicate.
                                            rules =
                                                compositeId("asset", "projectId", "type", "time", "rewards") +
                                                    counterparty("asset", "Binance Earn Rewards"),
                                        ),
                                )
                            },
                    // Binance's overall REST budget is ~6000 request-weight/min; 350ms between requests
                    // (scaled per endpoint by requestCostWeight) stays comfortably inside that for a
                    // personal read-only key.
                    rateLimitMillis = 350L,
                    rateLimitErrorSubstrings = listOf("Too many requests", "Way too many requests", "-1003", "IP banned"),
                    maxRateLimitRetries = 6,
                    tokenPageUrl = "https://www.binance.com/en/my/settings/api-management",
                    connectInstructions =
                        listOf(
                            "Open the Binance API Management page in your browser and sign in.",
                            "Create a new API key (System generated) and complete the security verification.",
                            "Enable only \"Enable Reading\" for the key's permissions — leave Spot & Margin " +
                                "Trading and Withdrawals off. Binance only allows an unrestricted-IP key to be " +
                                "read-only, so this is the only option offered unless you also add an IP " +
                                "access restriction.",
                            "Copy the API key and paste it below as the API key.",
                            "Copy the Secret Key — shown only once — and paste it below as the API secret.",
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }
}
