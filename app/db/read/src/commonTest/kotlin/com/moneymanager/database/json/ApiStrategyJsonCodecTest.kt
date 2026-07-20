package com.moneymanager.database.json

import com.moneymanager.domain.model.apistrategy.ApiAccountBridge
import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAuthType
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiInternalTransferReconcile
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiPeopleMappings
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiRequestSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiSyntheticAccount
import com.moneymanager.domain.model.apistrategy.ApiTradeMappings
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.BodyFormat
import com.moneymanager.domain.model.apistrategy.FieldPlacement
import com.moneymanager.domain.model.apistrategy.HttpMethodType
import com.moneymanager.domain.model.apistrategy.NonceSpec
import com.moneymanager.domain.model.apistrategy.PaginationMode
import com.moneymanager.domain.model.apistrategy.SecretEncoding
import com.moneymanager.domain.model.apistrategy.SigFieldLocation
import com.moneymanager.domain.model.apistrategy.SigPart
import com.moneymanager.domain.model.apistrategy.SignatureEncoding
import com.moneymanager.domain.model.apistrategy.SigningAlgorithm
import com.moneymanager.domain.model.apistrategy.TimestampFormat
import com.moneymanager.domain.model.apistrategy.TransferDirection
import com.moneymanager.domain.model.apistrategy.WindowBoundFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiStrategyJsonCodecTest {
    private fun config(pagination: ApiPaginationConfig?) =
        ApiStrategyConfigJson(
            baseUrl = "https://example.com",
            authType = ApiAuthType.BEARER_TOKEN,
            accountsEndpoint = ApiEndpointConfig(path = "/accounts", responseArrayKey = "accounts"),
            transactionsEndpoint =
                ApiEndpointConfig(
                    path = "/transactions",
                    responseArrayKey = "transactions",
                    queryParams = listOf(ApiQueryParam(name = "account_id", dynamicSource = "account.id")),
                    pagination = pagination,
                ),
            accountMappings = ApiAccountMappings(),
            transactionMappings = ApiTransactionMappings(),
        )

    @Test
    fun `cursor pagination round-trips`() {
        val original = config(ApiPaginationConfig())
        val decoded = ApiStrategyJsonCodec.decode(ApiStrategyJsonCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals(PaginationMode.CURSOR, decoded.transactionsEndpoint.pagination?.mode)
    }

    @Test
    fun `people mappings ephemeral counterparty id prefixes round-trip`() {
        val original =
            config(ApiPaginationConfig())
                .copy(peopleMappings = ApiPeopleMappings(ephemeralCounterpartyIdPrefixes = setOf("anonuser_")))
        val decoded = ApiStrategyJsonCodec.decode(ApiStrategyJsonCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals(setOf("anonuser_"), decoded.peopleMappings.ephemeralCounterpartyIdPrefixes)
    }

    @Test
    fun `date-window pagination round-trips`() {
        val original =
            config(
                ApiPaginationConfig(
                    mode = PaginationMode.DATE_WINDOW,
                    extraParams = listOf(ApiQueryParam(name = "type", value = "FLAT")),
                ),
            )
        val decoded = ApiStrategyJsonCodec.decode(ApiStrategyJsonCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals(PaginationMode.DATE_WINDOW, decoded.transactionsEndpoint.pagination?.mode)
    }

    @Test
    fun `exchange signing recipe and data endpoints round-trip`() {
        val original =
            config(null).copy(
                authType = ApiAuthType.SIGNED,
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
                syntheticAccount = ApiSyntheticAccount(name = "Crypto.com Exchange", externalId = "crypto-com-exchange"),
                internalTransferReconcile =
                    ApiInternalTransferReconcile(
                        bridges = listOf(ApiAccountBridge(otherAccountName = "Crypto.com")),
                        windowSeconds = 3600,
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
                                    sideField = "side",
                                    baseQuantityField = "traded_quantity",
                                    priceField = "traded_price",
                                    timestampField = "create_time",
                                    timestampFormat = TimestampFormat.EPOCH_MS,
                                    idField = "trade_id",
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
    fun `kraken-shaped keyed-object, offset paging, enrichment and asset aliases round-trip`() {
        val original =
            config(null).copy(
                authType = ApiAuthType.SIGNED,
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
                syntheticAccount = ApiSyntheticAccount(name = "Kraken", externalId = "kraken"),
                assetAliases = mapOf("XXBT" to "BTC", "ZUSD" to "USD"),
                rateLimitMillis = 3_100L,
                rateLimitErrorSubstrings = listOf("Rate limit exceeded", "Throttled"),
                rateLimitBackoffMillis = 5_000L,
                maxRateLimitRetries = 6,
                assetSuffixesToStrip = setOf(".F", ".S", ".M"),
                minorUnitDivisorOverrides = mapOf("GBP" to 1000L),
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
                            fixedDirection = TransferDirection.IN,
                            transactionMappings =
                                ApiTransactionMappings(
                                    idField = "ledger_id",
                                    joinKeyField = "refid",
                                    counterpartyAliasField = "address",
                                    counterpartyAccountAliases = mapOf("INTERNAL_DEPOSIT" to "Kraken App"),
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
                                            mode = PaginationMode.DATE_WINDOW,
                                            startParam = "start",
                                            endParam = "end",
                                            windowBoundFormat = WindowBoundFormat.EPOCH_S,
                                            offsetParam = "ofs",
                                            limitValue = 50,
                                            totalCountField = "result.count",
                                        ),
                                ),
                            kind = ApiEndpointKind.TRADES,
                            tradeMappings =
                                ApiTradeMappings(
                                    instrumentField = "pair",
                                    sideField = "type",
                                    baseQuantityField = "vol",
                                    quoteQuantityField = "cost",
                                    timestampField = "time",
                                    timestampFormat = TimestampFormat.EPOCH_S_FLOAT,
                                    idField = "trade_id",
                                ),
                        ),
                    ),
            )
        val decoded = ApiStrategyJsonCodec.decode(ApiStrategyJsonCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals(mapOf("XXBT" to "BTC", "ZUSD" to "USD"), decoded.assetAliases)
        assertEquals(
            WindowBoundFormat.EPOCH_S,
            decoded.dataEndpoints[2]
                .endpoint.pagination
                ?.windowBoundFormat,
        )
        assertEquals(
            "ofs",
            decoded.dataEndpoints[2]
                .endpoint.pagination
                ?.offsetParam,
        )
        assertTrue(decoded.dataEndpoints[2].endpoint.responseObjectValues)
        assertEquals("ledger_id", decoded.dataEndpoints[1].endpoint.itemKeyField)
        assertEquals("refid", decoded.dataEndpoints[1].transactionMappings?.joinKeyField)
        assertTrue(decoded.dataEndpoints[0].enrichesTransfers)
    }

    @Test
    fun `legacy pagination without a mode discriminator decodes as cursor`() {
        // Pagination JSON persisted before the `mode` field existed: a flat cursor object.
        val legacyJson =
            """
            {
              "baseUrl": "https://example.com",
              "authType": "BEARER_TOKEN",
              "accountsEndpoint": { "path": "/accounts", "responseArrayKey": "accounts" },
              "transactionsEndpoint": {
                "path": "/transactions",
                "responseArrayKey": "transactions",
                "queryParams": [ { "name": "account_id", "dynamicSource": "account.id" } ],
                "pagination": { "limitParam": "limit", "limitValue": 100, "cursorParam": "before", "cursorResponseField": "created" }
              },
              "accountMappings": {},
              "transactionMappings": {}
            }
            """.trimIndent()

        val decoded = ApiStrategyJsonCodec.decode(legacyJson)
        val pagination = decoded.transactionsEndpoint.pagination
        assertEquals(PaginationMode.CURSOR, pagination?.mode)
        assertEquals("before", pagination?.cursorParam)
        assertEquals("created", pagination?.cursorResponseField)
        assertEquals(100, pagination?.limitValue)
    }
}
