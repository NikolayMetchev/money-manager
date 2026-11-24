package com.moneymanager.builtin

import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiFanOut
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiLedgerTrades
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiPaging
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiRequestSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.ApiValueSet
import com.moneymanager.domain.model.apistrategy.JwtAlgorithm
import com.moneymanager.domain.model.apistrategy.JwtField
import com.moneymanager.domain.model.apistrategy.JwtSigningConfig
import com.moneymanager.domain.model.apistrategy.TimestampFormat
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.Extraction
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal object CoinbaseApiStrategy {
    private val STRATEGY_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000c")

    /**
     * Built-in Coinbase strategy — pure config over the generic signed-exchange engine (no provider code).
     * Coinbase CDP API keys authenticate each request with a freshly signed JWT (see [JwtSigningConfig]),
     * EdDSA for an Ed25519 key (the portal's default) or ES256 for an ECDSA one; the key's id or name is the
     * api key, its private key the secret.
     *
     * Every wallet (`v2/accounts`) has its own ledger (`v2/accounts/{id}/transactions`, fanned out per
     * wallet id and walked by `starting_after` token, newest first), and together those ledgers are the
     * whole account. Deposits, withdrawals, sends, receives and rewards are signed ledger rows. Buys,
     * sells, converts and Advanced Trade fills post one row per wallet they touch, sharing the
     * `buy`/`sell`/`trade` id or Advanced Trade order id, so they are grouped back into trades (see
     * [ApiTransactionMappings.ledgerTrades]) at the exact amounts that settled; a purchase
     * paid by card posts only the crypto row, and is booked against its `native_amount`. The Advanced
     * Trade fills endpoint is deliberately not used: an order placed as "spend £X" reports its fills'
     * `size` in the quote asset, and the ledger already has the settled amounts.
     *
     * Field paths follow the Coinbase docs; verify against a live response when connecting real keys
     * (same caveat as the other exchange built-ins).
     */
    fun strategy(now: Instant): ApiImportStrategy {
        // Page size goes on the wire via the pagination config, not as a static query param, so each
        // endpoint's key stays its bare path (FromValueEndpoint below references "v2/accounts" by it).
        fun tokenPaging(
            cursorParam: String,
            nextCursorField: String,
            positionField: String? = null,
        ) = ApiPaginationConfig(
            paging = ApiPaging.Token(tokenField = nextCursorField, param = cursorParam, positionField = positionField),
            // Default page size ("limit" = 100), sent on the wire.
            sendLimitParam = true,
        )

        val ledgerMappings =
            ApiTransactionMappings(
                amountField = "amount.amount",
                currencyField = "amount.currency",
                amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                timestampField = "created_at",
                timestampFormat = TimestampFormat.ISO_8601,
                descriptionField = "details.title",
                counterpartyAddressField = "to.address",
                counterpartyNetworkField = "network.network_name",
                txidField = "network.hash",
                ledgerTrades =
                    ApiLedgerTrades(
                        key = ValueExpr(listOf("trade.id", "buy.id", "sell.id", "advanced_trade_fill.order_id")),
                        unpairedCounterAmountPath = "native_amount",
                        unpairedFundingAccountName = "Coinbase Payment Methods",
                    ),
                rules =
                    onlyWhen(Condition("status", ConditionOp.EQUALS, value = "completed")) +
                        // Every row is one wallet's own signed movement (negative = out).
                        directionFromSign("amount.amount") +
                        // Buys/sells/converts and Advanced Trade fills: one row per wallet touched, grouped into
                        // a trade by the id of the buy/sell/convert (or the Advanced Trade order) they belong to
                        // rather than booked as transfers. The rows carry the exact settled amounts, so no
                        // quantity is ever derived from a price.
                        rule(
                            RuleAction.LedgerTradeLeg,
                            conditions =
                                listOf(
                                    Condition("type", ConditionOp.IN, value = "advanced_trade_fill,buy,retail_simple_dust,sell,trade"),
                                ),
                        ) +
                        // An Advanced Trade fill's commission is settled outside its legs, and both legs repeat it:
                        // book it once, on the leg in the pair's quote asset.
                        fee(
                            FeeRule(
                                amount = ValueExpr(listOf("advanced_trade_fill.commission")),
                                // Charged once, on the leg in the pair's quote asset: what follows the symbol's last "-".
                                chargedOnAsset = ValueExpr(listOf("advanced_trade_fill.product_id"), Extraction("^.*-(.*)$", "$1")),
                            ),
                        ),
            )

        return ApiImportStrategy(
            id = ApiImportStrategyId(STRATEGY_ID),
            name = "Coinbase",
            config =
                ApiStrategyConfig(
                    baseUrl = "https://api.coinbase.com",
                    requestSigning =
                        ApiRequestSigningConfig(
                            jwt =
                                JwtSigningConfig(
                                    // CDP keys are Ed25519 by default and ECDSA on request; sign as whichever
                                    // the pasted secret is.
                                    algorithm = JwtAlgorithm.DETECT,
                                    header =
                                        listOf(
                                            JwtField("alg", "{alg}"),
                                            JwtField("kid", "{apiKey}"),
                                            JwtField("nonce", "{nonceHex}"),
                                            JwtField("typ", "JWT"),
                                        ),
                                    claims =
                                        listOf(
                                            JwtField("iss", "cdp"),
                                            JwtField("sub", "{apiKey}"),
                                            JwtField("nbf", "{now}", numeric = true),
                                            JwtField("exp", "{exp}", numeric = true),
                                            JwtField("uri", "{method} {host}{path}"),
                                        ),
                                ),
                        ),
                    accounts = ApiAccountsSource.Single(name = "Coinbase", externalId = "coinbase"),
                    valueEndpoints =
                        listOf(
                            ApiEndpointConfig(
                                path = "v2/accounts",
                                responseArrayKey = "data",
                                pagination = tokenPaging("starting_after", "pagination.next_starting_after", positionField = "created"),
                            ),
                        ),
                    dataEndpoints =
                        listOf(
                            ApiDataEndpoint(
                                ApiEndpointConfig(
                                    path = "v2/accounts/{fanOut}/transactions",
                                    responseArrayKey = "data",
                                    // Newest first, so an incremental walk can stop at the watermark.
                                    queryParams = listOf(ApiQueryParam(name = "order", value = "desc")),
                                    pagination =
                                        tokenPaging("starting_after", "pagination.next_starting_after", positionField = "created_at"),
                                    // Wallet ids are lowercase UUIDs, substituted into the path.
                                    fanOut =
                                        ApiFanOut(
                                            param = null,
                                            values = ApiValueSet.FromValueEndpoint("v2/accounts", listOf("id")),
                                            preserveCase = true,
                                        ),
                                ),
                                ApiEndpointKind.DEPOSITS,
                                transactionMappings = ledgerMappings,
                            ),
                        ),
                    // v2 allows 10,000 requests an hour per key (~2.8/s).
                    rateLimitMillis = 400L,
                    rateLimitErrorSubstrings = listOf("rate_limit_exceeded", "Too Many Requests"),
                    tokenPageUrl = "https://portal.cdp.coinbase.com/projects/api-keys",
                    connectInstructions =
                        listOf(
                            "Open the Coinbase Developer Platform API keys page in your browser and sign in with " +
                                "your Coinbase account.",
                            "Create a Secret API key (Ed25519, the default, or ECDSA) and grant only the View permission.",
                            "Paste the API key ID (or, for an older key, its name organizations/…/apiKeys/…) below as the API key.",
                            "Paste the API secret below as the private key, or load the downloaded key file.",
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }
}
