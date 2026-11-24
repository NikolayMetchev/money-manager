package com.moneymanager.builtin

import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiDateWindowing
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiLedgerTrades
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
import com.moneymanager.domain.model.apistrategy.NonceFormat
import com.moneymanager.domain.model.apistrategy.NonceSpec
import com.moneymanager.domain.model.apistrategy.SecretEncoding
import com.moneymanager.domain.model.apistrategy.SigFieldLocation
import com.moneymanager.domain.model.apistrategy.SigPart
import com.moneymanager.domain.model.apistrategy.SignatureEncoding
import com.moneymanager.domain.model.apistrategy.SigningAlgorithm
import com.moneymanager.domain.model.apistrategy.TimestampFormat
import com.moneymanager.domain.model.apistrategy.WindowBoundFormat
import com.moneymanager.domain.model.rules.AssetCodeRules
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal object KrakenApiStrategy {
    private val STRATEGY_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000a")

    /**
     * Built-in Kraken API strategy — pure config over the generic signed-exchange engine (no provider
     * code). Kraken's REST auth is HMAC-SHA512 over `path + SHA256(nonce + form-body)`, with a
     * Base64-decoded secret and Base64 signature — the [ApiRequestSigningConfig] KDoc covers exactly
     * this shape. Trades come from `TradesHistory`; deposits/withdrawals come from the single `Ledgers`
     * endpoint filtered by `type` (deposit/withdrawal), enriched with on-chain address/txid from
     * `DepositStatus`/`WithdrawStatus` (endpoints that supply no money movement of their own, just
     * enrichment — see [ApiDataEndpoint.enrichesTransfers]). Both TradesHistory and Ledgers return a
     * JSON object keyed by id (not an array) and cap ~50 rows/response, paged by an integer `ofs`.
     *
     * Field paths follow the Kraken REST v0 docs; verify against a live response when connecting real
     * keys (same caveat as the Crypto.com built-in).
     */
    fun strategy(now: Instant): ApiImportStrategy {
        // Both TradesHistory and Ledgers page the same way: a date window (Kraken's start/end are whole
        // seconds, not millis) further paged by an offset ("ofs") in `limitValue`-sized chunks, bounded
        // by the response's total `result.count`.
        val historyWindow =
            ApiPaginationConfig(
                window = ApiDateWindowing(startParam = "start", endParam = "end", windowDays = 90, boundFormat = WindowBoundFormat.EPOCH_S),
                paging = ApiPaging.Offset(param = "ofs", totalCountField = "result.count"),
                limitValue = 50,
            )

        fun signed(
            path: String,
            key: String,
            pagination: ApiPaginationConfig? = historyWindow,
            responseObjectValues: Boolean = false,
            itemKeyField: String? = null,
            queryParams: List<ApiQueryParam> = emptyList(),
            // Kraken's ledger/trade-history calls cost 2 rate-limit counter units against 1 for other
            // endpoints (per Kraken's published REST rate-limit docs); TradesHistory/Ledgers are the
            // only callers relying on this default, DepositStatus/WithdrawStatus pass 1 explicitly.
            requestCostWeight: Int = 2,
        ) = ApiEndpointConfig(
            path = path,
            responseArrayKey = key,
            queryParams = queryParams,
            method = HttpMethodType.POST,
            // Kraken signals success/failure via an empty/absent "error" array, never a status code.
            errorArrayField = "error",
            pagination = pagination,
            responseObjectValues = responseObjectValues,
            itemKeyField = itemKeyField,
            requestCostWeight = requestCostWeight,
        )

        val tradeMappings =
            ApiTradeMappings(
                instrumentField = "pair",
                splitMode = InstrumentSplitMode.QUOTE_SUFFIX,
                // The longest matching suffix always wins (see splitInstrument), so shorter codes that
                // are also suffixes of longer ones (e.g. "ZUSD" ends with "USD") are listed safely.
                // "XXBT"/"XETH" cover crypto/crypto pairs quoted in BTC or ETH using Kraken's legacy long
                // codes (e.g. "XETHXXBT" is ETH/BTC); "BTC"/"ETH" cover the same case for pairs that use
                // the short code instead (e.g. "ETHWETH" is ETHW/ETH, "PAXGETH" is PAXG/ETH) — Kraken is
                // inconsistent about which form a given pair uses. "PYUSD" (and other multi-char
                // stablecoins) must be listed or a pair like "XBTPYUSD" wrongly matches the shorter "USD"
                // suffix, splitting as XBTPY/USD instead of XBT/PYUSD.
                quoteAssets =
                    listOf(
                        "XXBT",
                        "XETH",
                        "ZUSD",
                        "ZEUR",
                        "ZGBP",
                        "ZCAD",
                        "ZJPY",
                        "ZCHF",
                        "ZAUD",
                        "PYUSD",
                        "USDT",
                        "USDC",
                        "USDG",
                        "RLUSD",
                        "TUSD",
                        "EURT",
                        "DAI",
                        "XBT",
                        "BTC",
                        "ETH",
                        "USD",
                        "EUR",
                        "GBP",
                    ),
                baseQuantityField = "vol",
                quoteQuantityField = "cost",
                // No fee: TradesHistory's own fee is a quote-currency report that can disagree with
                // (or duplicate) what was actually charged — sometimes in a different asset entirely (a
                // base-asset settlement fee Kraken bills separately). The Ledgers `type=all` feed already
                // supplies every fee authoritatively (see ledgerMappings' fee), so the trade
                // path books none of its own.
                timestampField = "time",
                timestampFormat = TimestampFormat.EPOCH_S_FLOAT,
                idField = "trade_id",
                orderIdField = "ordertxid",
                rules = tradeSideFrom("type", "buy"),
            )

        fun ledgerMappings(joinKey: String) =
            ApiTransactionMappings(
                currencyField = "asset",
                timestampField = "time",
                timestampFormat = TimestampFormat.EPOCH_S_FLOAT,
                // Ledger entries carry their id only as the response object's key, spliced in by
                // itemKeyField below under this field name.
                idField = "ledger_id",
                amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                joinKeyField = joinKey,
                // Only meaningful on the `type=trade` rows (see the ledger trade leg rule below) — refid
                // equals the matching TradesHistory trade's own id.
                ledgerTrades = ApiLedgerTrades(key = ValueExpr(listOf("refid"))),
                // A failed/cancelled deposit or withdrawal appears as two ledger rows sharing one refid
                // with opposite-signed amounts (the debit and its reversal), netting to zero. Kraken's
                // "amount" is signed (negative = out), so trust that sign instead of the endpoint's fixed
                // direction, or the reversal double-books as a second real movement in the same direction.
                rules =
                    buildList {
                        addAll(directionFromSign("amount"))
                        add(fee(FeeRule(amount = ValueExpr(listOf("fee")))))
                        // `type=all` also returns the trades TradesHistory already supplies: those rows are
                        // ledger trade legs, not transfers.
                        add(rule(RuleAction.LedgerTradeLeg, conditions = listOf(Condition("type", ConditionOp.IN, value = "trade"))))
                        // Every row reports what its asset holds after it, trade rows included. An Earn sub-holding's
                        // row (`XETH.F`, `.S`, `.M`) reports that sub-holding alone, while its asset books into the one
                        // Kraken account with spot (see assetCodes), so only spot rows report the account's balance.
                        add(balance("balance", conditions = listOf(Condition("asset", ConditionOp.MATCHES, value = "^[^.]+$"))))
                    },
            )

        // DepositStatus/WithdrawStatus supply no money movement of their own — they only enrich the
        // Ledgers-sourced transfer that shares the same refid with on-chain address/txid.
        val enrichMappings =
            ApiTransactionMappings(
                idField = "refid",
                counterpartyAddressField = "info",
                txidField = "txid",
            )

        return ApiImportStrategy(
            id = ApiImportStrategyId(STRATEGY_ID),
            name = "Kraken",
            config =
                ApiStrategyConfig(
                    baseUrl = "https://api.kraken.com",
                    requestSigning =
                        ApiRequestSigningConfig(
                            algorithm = SigningAlgorithm.HMAC_SHA512,
                            secretEncoding = SecretEncoding.BASE64,
                            signatureEncoding = SignatureEncoding.BASE64,
                            message = listOf(SigPart.Path, SigPart.Sha256(listOf(SigPart.Nonce, SigPart.Body))),
                            apiKey = FieldPlacement(SigFieldLocation.HEADER, "API-Key"),
                            nonce = NonceSpec(NonceFormat.EPOCH_MS, FieldPlacement(SigFieldLocation.BODY_FIELD, "nonce")),
                            signature = FieldPlacement(SigFieldLocation.HEADER, "API-Sign"),
                            bodyFormat = BodyFormat.FORM_URLENCODED,
                        ),
                    accounts = ApiAccountsSource.Single(name = "Kraken", externalId = "kraken"),
                    dataEndpoints =
                        listOf(
                            ApiDataEndpoint(
                                // Splice the response object's own key (e.g. "STVCTCR-ERSZJ-HBXQB2") over the
                                // "trade_id" field before mapping: the native `trade_id` is a small per-fill
                                // sequence number that collides across unrelated trades (observed repeatedly as
                                // 0), whereas the key is unique and — critically — is the same identifier
                                // Kraken's Ledgers rows carry as `refid`, letting ledgerTrades join
                                // a trade to its authoritative ledger legs (see ledgerMappings).
                                signed(
                                    "0/private/TradesHistory",
                                    "result.trades",
                                    responseObjectValues = true,
                                    itemKeyField = "trade_id",
                                ),
                                ApiEndpointKind.TRADES,
                                tradeMappings = tradeMappings,
                            ),
                            // Every non-trade ledger movement in one pass. Kraken's Ledgers `type` enum is
                            // `all, trade, deposit, withdrawal, transfer, margin, adjustment, rollover, credit,
                            // settled, staking, dividend, sale, nft_rebate` (default "all") — earlier revisions
                            // of this strategy only requested deposit/withdrawal/reward/staking, so any balance
                            // movement Kraken books under transfer/margin/adjustment/rollover/credit/settled/
                            // sale/nft_rebate (Earn subscribe/unsubscribe, internal moves, fee rebates, etc.) was
                            // invisible to the importer — the account balance would silently drift from the true
                            // Kraken balance by exactly the missed amount. `type=all` also returns `trade`-type
                            // entries that duplicate what TradesHistory already supplies, so those become ledger
                            // trade legs. Direction comes from the signed `amount` field
                            // (an amount-sign direction), not the ledger `type`, so this single endpoint covers
                            // every type without per-type direction mapping — including the historical "reward"
                            // vs "staking" naming inconsistency between Kraken account vintages.
                            ApiDataEndpoint(
                                signed(
                                    "0/private/Ledgers",
                                    "result.ledger",
                                    responseObjectValues = true,
                                    itemKeyField = "ledger_id",
                                    queryParams = listOf(ApiQueryParam(name = "type", value = "all")),
                                ),
                                ApiEndpointKind.DEPOSITS,
                                transactionMappings = ledgerMappings("refid"),
                            ),
                            // Known limitation: Kraken pages these funding-status endpoints with an opaque cursor
                            // token, so only the first page is fetched here — enrichment (on-chain address/txid)
                            // beyond that page is silently skipped, though the underlying deposit/withdrawal
                            // transfer itself (from Ledgers, above) is unaffected. ApiPaging.Token can express it,
                            // but the cursor's field name needs verifying against a live response first.
                            ApiDataEndpoint(
                                signed("0/private/DepositStatus", "result", pagination = null, requestCostWeight = 1),
                                ApiEndpointKind.DEPOSITS,
                                transactionMappings = enrichMappings,
                                enrichesTransfers = true,
                            ),
                            ApiDataEndpoint(
                                signed("0/private/WithdrawStatus", "result", pagination = null, requestCostWeight = 1),
                                ApiEndpointKind.WITHDRAWALS,
                                transactionMappings = enrichMappings,
                                enrichesTransfers = true,
                            ),
                        ),
                    // Kraken Earn holdings use a suffixed asset code for the same underlying asset (e.g. the
                    // "Flexible Earn" ETH position is "XETH.F", staked is "XETH.S"); strip it so the position's
                    // deposit/withdrawal ledger entries resolve to the ordinary "ETH" asset like any other.
                    assetCodes = AssetCodeRules(stripSuffixes = setOf(".F", ".S", ".M")),
                    // Starter-tier decay is 0.33 counter/sec (Kraken's slowest verification tier), so 1 unit of
                    // cost needs ~3.03s to fully decay; 3100ms per unit keeps even the slowest tier clear of
                    // "EAPI:Rate limit exceeded" with a small margin. requestCostWeight above scales this per
                    // endpoint (2 for ledger/trade-history calls, 1 for the rest) so cheaper endpoints aren't
                    // paced as conservatively as the most expensive ones.
                    rateLimitMillis = 3_100L,
                    rateLimitErrorSubstrings = listOf("Rate limit exceeded", "Too many requests", "Throttled"),
                    maxRateLimitRetries = 6,
                    tokenPageUrl = "https://pro.kraken.com/app/settings/api",
                    connectInstructions =
                        listOf(
                            "Open the Kraken API management page in your browser and sign in.",
                            "Create a new API key with the \"Query Funds\", \"Query Ledger Entries\", " +
                                "\"Query Open/Closed Orders & Trades\" and \"Export Data\" permissions (read-only " +
                                "access is sufficient; do not grant withdrawal or trading permissions).",
                            "Copy the API key and paste it below as the API key.",
                            "Copy the Private Key and paste it below as the API secret.",
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }
}
