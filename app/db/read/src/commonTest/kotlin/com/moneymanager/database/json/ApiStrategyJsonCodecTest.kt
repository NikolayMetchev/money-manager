package com.moneymanager.database.json

import com.moneymanager.domain.model.apistrategy.ApiAccountBridge
import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiDateWindowing
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiInternalTransferReconcile
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiPaging
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiRequestSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.ApiTradeMappings
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.BodyFormat
import com.moneymanager.domain.model.apistrategy.FieldPlacement
import com.moneymanager.domain.model.apistrategy.HttpMethodType
import com.moneymanager.domain.model.apistrategy.InstrumentSplitMode
import com.moneymanager.domain.model.apistrategy.NonceSpec
import com.moneymanager.domain.model.apistrategy.SecretEncoding
import com.moneymanager.domain.model.apistrategy.SigFieldLocation
import com.moneymanager.domain.model.apistrategy.SigPart
import com.moneymanager.domain.model.apistrategy.SignatureEncoding
import com.moneymanager.domain.model.apistrategy.SigningAlgorithm
import com.moneymanager.domain.model.apistrategy.TimestampFormat
import com.moneymanager.domain.model.apistrategy.WindowBoundFormat
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.AssetCodeRules
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiStrategyJsonCodecTest {
    private fun config(pagination: ApiPaginationConfig?) =
        ApiStrategyConfig(
            baseUrl = "https://example.com",
            accounts =
                ApiAccountsSource.Downloaded(
                    endpoint = ApiEndpointConfig(path = "/accounts", responseArrayKey = "accounts"),
                    mappings = ApiAccountMappings(),
                ),
            dataEndpoints =
                listOf(
                    ApiDataEndpoint(
                        endpoint =
                            ApiEndpointConfig(
                                path = "/transactions",
                                responseArrayKey = "transactions",
                                queryParams = listOf(ApiQueryParam(name = "account_id", dynamicSource = "account.id")),
                                pagination = pagination,
                            ),
                        kind = ApiEndpointKind.BANK_TRANSACTIONS,
                        transactionMappings = ApiTransactionMappings(),
                    ),
                ),
        )

    @Test
    fun `cursor pagination round-trips`() {
        val original = config(ApiPaginationConfig(paging = ApiPaging.BeforeCursor(), sendLimitParam = true))
        val decoded = ApiStrategyJsonCodec.decode(ApiStrategyJsonCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals(
            ApiPaging.BeforeCursor(),
            decoded.bankTransactions
                ?.endpoint
                ?.pagination
                ?.paging,
        )
    }

    @Test
    fun `account rules round-trip`() {
        val rules =
            listOf(
                StrategyRule(
                    conditions = listOf(Condition("owners[1].user_id", ConditionOp.EXISTS)),
                    actions = listOf(RuleAction.Account(AccountSide.OWN, AccountNaming(ValueExpr.of("id"), name = "Monzo Joint"))),
                ),
                StrategyRule(actions = listOf(RuleAction.ExternalId(RuleValue.Template("monzo:{id}")))),
            )
        val original =
            config(ApiPaginationConfig(paging = ApiPaging.BeforeCursor(), sendLimitParam = true)).let { config ->
                val accounts = config.accounts as ApiAccountsSource.Downloaded
                config.copy(accounts = accounts.copy(mappings = accounts.mappings.copy(rules = rules)))
            }
        val decoded = ApiStrategyJsonCodec.decode(ApiStrategyJsonCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals(rules, (decoded.accounts as ApiAccountsSource.Downloaded).mappings.rules)
    }

    @Test
    fun `date-window pagination round-trips`() {
        val original =
            config(
                ApiPaginationConfig(
                    window = ApiDateWindowing(boundFormat = WindowBoundFormat.ISO_8601),
                    extraParams = listOf(ApiQueryParam(name = "type", value = "FLAT")),
                ),
            )
        val decoded = ApiStrategyJsonCodec.decode(ApiStrategyJsonCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals(
            WindowBoundFormat.ISO_8601,
            decoded.bankTransactions
                ?.endpoint
                ?.pagination
                ?.window
                ?.boundFormat,
        )
    }

    @Test
    fun `exchange signing recipe and data endpoints round-trip`() {
        val original =
            config(null).copy(
                requestSigning =
                    ApiRequestSigningConfig(
                        algorithm = SigningAlgorithm.HMAC_SHA512,
                        secretEncoding = SecretEncoding.BASE64,
                        signatureEncoding = SignatureEncoding.BASE64,
                        // Kraken-shaped message with a nested SHA-256 to exercise the recursive SigPart.
                        message = listOf(SigPart.Path, SigPart.Sha256(listOf(SigPart.Nonce, SigPart.Body))),
                        apiKey = FieldPlacement(SigFieldLocation.HEADER, "API-Key"),
                        nonce = NonceSpec(placement = FieldPlacement(SigFieldLocation.BODY_FIELD, "nonce")),
                        signature = FieldPlacement(SigFieldLocation.HEADER, "API-Sign"),
                        bodyFormat = BodyFormat.FORM_URLENCODED,
                    ),
                accounts = ApiAccountsSource.Single(name = "Crypto.com Exchange", externalId = "crypto-com-exchange"),
                internalTransferReconcile =
                    ApiInternalTransferReconcile(
                        bridges = listOf(ApiAccountBridge(otherAccountName = "Crypto.com")),
                        window = SettlementWindow.Elapsed(3600),
                        amountTolerancePercent = "1",
                    ),
                dataEndpoints =
                    listOf(
                        ApiDataEndpoint(
                            endpoint =
                                ApiEndpointConfig(
                                    path = "private/get-trades",
                                    responseArrayKey = "result.data",
                                    method = HttpMethodType.POST,
                                    successCodeField = "code",
                                    successCodeOkValue = "0",
                                ),
                            kind = ApiEndpointKind.TRADES,
                            tradeMappings =
                                ApiTradeMappings(
                                    instrumentField = "instrument_name",
                                    baseQuantityField = "traded_quantity",
                                    priceField = "traded_price",
                                    timestampField = "create_time",
                                    timestampFormat = TimestampFormat.EPOCH_MS,
                                    idField = "trade_id",
                                    rules =
                                        listOf(
                                            StrategyRule(
                                                conditions = listOf(Condition("side", ConditionOp.IN, "BUY")),
                                                actions = listOf(RuleAction.TradeSide(buy = true)),
                                            ),
                                            StrategyRule(actions = listOf(RuleAction.TradeSide(buy = false))),
                                        ),
                                ),
                        ),
                    ),
            )
        val decoded = ApiStrategyJsonCodec.decode(ApiStrategyJsonCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals(SigningAlgorithm.HMAC_SHA512, decoded.requestSigning?.algorithm)
        assertEquals(
            "result.data",
            decoded.dataEndpoints
                .single()
                .endpoint.responseArrayKey,
        )
        assertEquals(
            HttpMethodType.POST,
            decoded.dataEndpoints
                .single()
                .endpoint.method,
        )
    }

    @Test
    fun `kraken-shaped keyed-object - offset paging - enrichment and asset aliases round-trip`() {
        val original =
            config(null).copy(
                requestSigning =
                    ApiRequestSigningConfig(
                        algorithm = SigningAlgorithm.HMAC_SHA512,
                        secretEncoding = SecretEncoding.BASE64,
                        signatureEncoding = SignatureEncoding.BASE64,
                        message = listOf(SigPart.Path, SigPart.Sha256(listOf(SigPart.Nonce, SigPart.Body))),
                        apiKey = FieldPlacement(SigFieldLocation.HEADER, "API-Key"),
                        nonce = NonceSpec(placement = FieldPlacement(SigFieldLocation.BODY_FIELD, "nonce")),
                        signature = FieldPlacement(SigFieldLocation.HEADER, "API-Sign"),
                        bodyFormat = BodyFormat.FORM_URLENCODED,
                    ),
                accounts = ApiAccountsSource.Single(name = "Kraken", externalId = "kraken"),
                rateLimitMillis = 3_100L,
                rateLimitErrorSubstrings = listOf("Rate limit exceeded", "Throttled"),
                rateLimitBackoffMillis = 5_000L,
                maxRateLimitRetries = 6,
                assetCodes = AssetCodeRules(aliases = mapOf("XXBT" to "BTC", "ZUSD" to "USD"), stripSuffixes = setOf(".F", ".S", ".M")),
                minorUnitDecimalPlaces = mapOf("GBP" to 3),
                // dataEndpoints is decoded in canonical (sorted-by kind/path/responseArrayKey) order -
                // see SortedDataEndpointListSerializer - so the entries are listed here in that same
                // order: DEPOSITS "0/private/DepositStatus" < DEPOSITS "0/private/Ledgers" < TRADES.
                dataEndpoints =
                    listOf(
                        ApiDataEndpoint(
                            endpoint = ApiEndpointConfig(path = "0/private/DepositStatus", responseArrayKey = "result"),
                            kind = ApiEndpointKind.DEPOSITS,
                            transactionMappings = ApiTransactionMappings(idField = "refid", txidField = "txid"),
                            enrichesTransfers = true,
                        ),
                        ApiDataEndpoint(
                            endpoint =
                                ApiEndpointConfig(
                                    path = "0/private/Ledgers",
                                    responseArrayKey = "result.ledger",
                                    method = HttpMethodType.POST,
                                    errorArrayField = "error",
                                    responseObjectValues = true,
                                    itemKeyField = "ledger_id",
                                    queryParams = listOf(ApiQueryParam(name = "type", value = "deposit")),
                                ),
                            kind = ApiEndpointKind.DEPOSITS,
                            transactionMappings =
                                ApiTransactionMappings(
                                    idField = "ledger_id",
                                    joinKeyField = "refid",
                                    rules =
                                        listOf(
                                            StrategyRule(
                                                conditions = listOf(Condition("address", ConditionOp.EQUALS, "INTERNAL_DEPOSIT")),
                                                actions =
                                                    listOf(
                                                        RuleAction.SetValue("address", RuleValue.constant("")),
                                                        RuleAction.Account(
                                                            AccountSide.COUNTERPARTY,
                                                            AccountNaming(ValueExpr.of("asset"), pattern = "^", name = "Kraken App"),
                                                        ),
                                                    ),
                                            ),
                                        ),
                                ),
                        ),
                        ApiDataEndpoint(
                            endpoint =
                                ApiEndpointConfig(
                                    path = "0/private/TradesHistory",
                                    responseArrayKey = "result.trades",
                                    method = HttpMethodType.POST,
                                    errorArrayField = "error",
                                    responseObjectValues = true,
                                    pagination =
                                        ApiPaginationConfig(
                                            window =
                                                ApiDateWindowing(
                                                    startParam = "start",
                                                    endParam = "end",
                                                    boundFormat = WindowBoundFormat.EPOCH_S,
                                                ),
                                            paging = ApiPaging.Offset(param = "ofs", totalCountField = "result.count"),
                                            limitValue = 50,
                                            incrementalOverlapDays = 3,
                                        ),
                                ),
                            kind = ApiEndpointKind.TRADES,
                            tradeMappings =
                                ApiTradeMappings(
                                    instrumentField = "pair",
                                    baseQuantityField = "vol",
                                    quoteQuantityField = "cost",
                                    timestampField = "time",
                                    timestampFormat = TimestampFormat.EPOCH_S_FLOAT,
                                    idField = "trade_id",
                                    rules =
                                        listOf(
                                            StrategyRule(
                                                conditions = listOf(Condition("type", ConditionOp.EXISTS)),
                                                actions = listOf(RuleAction.TradeSide(buy = false)),
                                            ),
                                        ),
                                ),
                        ),
                    ),
            )
        val decoded = ApiStrategyJsonCodec.decode(ApiStrategyJsonCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals(mapOf("XXBT" to "BTC", "ZUSD" to "USD"), decoded.assetCodes.aliases)
        assertEquals(
            WindowBoundFormat.EPOCH_S,
            decoded.dataEndpoints[2]
                .endpoint.pagination
                ?.window
                ?.boundFormat,
        )
        assertEquals(
            ApiPaging.Offset(param = "ofs", totalCountField = "result.count"),
            decoded.dataEndpoints[2]
                .endpoint.pagination
                ?.paging,
        )
        assertTrue(decoded.dataEndpoints[2].endpoint.responseObjectValues)
        assertEquals("ledger_id", decoded.dataEndpoints[1].endpoint.itemKeyField)
        assertEquals("refid", decoded.dataEndpoints[1].transactionMappings?.joinKeyField)
        assertTrue(decoded.dataEndpoints[0].enrichesTransfers)
    }

    @Test
    fun `nested items - a set trade asset and a composite transfer id round-trip`() {
        val compositeId = "{asset}-{projectId}-{type}-{time}-{rewards}"
        val original =
            config(null).copy(
                accounts = ApiAccountsSource.Single(name = "Binance", externalId = "binance"),
                // Canonical (sorted) order: DEPOSITS before TRADES.
                dataEndpoints =
                    listOf(
                        ApiDataEndpoint(
                            endpoint =
                                ApiEndpointConfig(
                                    path = "sapi/v1/simple-earn/flexible/history/rewardsRecord",
                                    responseArrayKey = "rows",
                                    queryParams = listOf(ApiQueryParam(name = "type", value = "BONUS")),
                                ),
                            kind = ApiEndpointKind.DEPOSITS,
                            transactionMappings =
                                ApiTransactionMappings(
                                    amountField = "rewards",
                                    currencyField = "asset",
                                    rules =
                                        listOf(
                                            StrategyRule(actions = listOf(RuleAction.ExternalId(RuleValue.constant(compositeId)))),
                                            StrategyRule(
                                                actions =
                                                    listOf(
                                                        RuleAction.Account(
                                                            AccountSide.COUNTERPARTY,
                                                            AccountNaming(
                                                                ValueExpr.of("asset"),
                                                                pattern = "^",
                                                                name = "Binance Earn Rewards",
                                                            ),
                                                        ),
                                                    ),
                                            ),
                                        ),
                                ),
                        ),
                        ApiDataEndpoint(
                            endpoint =
                                ApiEndpointConfig(
                                    path = "sapi/v1/asset/dribblet",
                                    responseArrayKey = "userAssetDribblets",
                                    nestedItemsKey = "userAssetDribbletDetails",
                                ),
                            kind = ApiEndpointKind.TRADES,
                            tradeMappings =
                                ApiTradeMappings(
                                    instrumentField = "unused",
                                    splitMode = InstrumentSplitMode.EXPLICIT_FIELDS,
                                    baseAssetField = "toAsset",
                                    quoteAssetField = "fromAsset",
                                    baseQuantityField = "transferedAmount",
                                    quoteQuantityField = "amount",
                                    timestampField = "operateTime",
                                    idField = "transId",
                                    rules =
                                        listOf(
                                            StrategyRule(
                                                actions =
                                                    listOf(
                                                        RuleAction.SetValue("toAsset", RuleValue.constant("BNB")),
                                                        RuleAction.TradeSide(buy = true),
                                                    ),
                                            ),
                                        ),
                                ),
                        ),
                    ),
            )
        val decoded = ApiStrategyJsonCodec.decode(ApiStrategyJsonCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals("userAssetDribbletDetails", decoded.dataEndpoints[1].endpoint.nestedItemsKey)
        assertEquals(
            RuleAction.ExternalId(RuleValue.constant(compositeId)),
            decoded.dataEndpoints[0]
                .transactionMappings
                ?.rules
                ?.first()
                ?.actions
                ?.single(),
        )
    }
}
