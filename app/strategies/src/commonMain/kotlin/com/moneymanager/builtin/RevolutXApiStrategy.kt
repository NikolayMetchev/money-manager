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
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.ApiTradeMappings
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.BodyFormat
import com.moneymanager.domain.model.apistrategy.FieldPlacement
import com.moneymanager.domain.model.apistrategy.InstrumentSplitMode
import com.moneymanager.domain.model.apistrategy.NonceFormat
import com.moneymanager.domain.model.apistrategy.NonceSpec
import com.moneymanager.domain.model.apistrategy.SigFieldLocation
import com.moneymanager.domain.model.apistrategy.SigPart
import com.moneymanager.domain.model.apistrategy.SignatureEncoding
import com.moneymanager.domain.model.apistrategy.SigningAlgorithm
import com.moneymanager.domain.model.apistrategy.TimestampFormat
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.StrategyRule
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal object RevolutXApiStrategy {
    private val STRATEGY_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000f")

    /**
     * Built-in Revolut X strategy — pure config over the generic signed-exchange engine (no provider code).
     * Revolut X signs `timestamp + verb + path + query` with the user's own Ed25519 key (base64) and carries the
     * key, timestamp and signature in `X-Revx-*` headers. Its one ledger, `/transactions`, lists every movement
     * with a source (debited) and a destination (credited) leg, net of fees, and pages within at most 30 days
     * by an opaque `metadata.next_cursor`.
     *
     * The ledger is read twice, told apart by its `types` filter: buys and sells as trades of the destination
     * asset for the source asset (a sell's legs swapped first, so its base is what was sold), and every other
     * movement as a transfer of its one Revolut X leg: the leg whose account is `revolut_x`, as a `send` is any
     * move between the person's own accounts, either way. Failed and reverted movements are imported excluded from
     * balances (their status the reason); pending and cancelled ones are skipped.
     * Staked balances sit in "Revolut X Staking", so the Revolut X account matches the exchange's available
     * balances.
     *
     * Field paths follow the docs; verify against a live response when connecting real keys (same caveat as
     * the other exchange built-ins) — in particular whether a send's source amount already includes its fee,
     * which only the per-transaction detail endpoint reports.
     */
    fun strategy(now: Instant): ApiImportStrategy {
        // Every request costs one rate-limit token per day of its date range, against 1,000 tokens a minute,
        // so a full 30-day window may run about every 2s. Revolut X opened in 2024: three years covers it.
        val window =
            ApiPaginationConfig(
                window =
                    ApiDateWindowing(
                        startParam = "start_date",
                        endParam = "end_date",
                        windowDays = 30,
                        lookbackDays = 365 * 3,
                    ),
                paging = ApiPaging.Token(tokenField = "metadata.next_cursor", param = "cursor"),
                sendLimitParam = true,
            )

        fun transactions(types: String) =
            ApiEndpointConfig(
                path = "1.0/transactions",
                responseArrayKey = "data",
                queryParams = listOf(ApiQueryParam("types", types)),
                pagination = window,
            )

        fun type(values: String) = Condition("type", ConditionOp.IN, values)

        // A failed or reverted movement is kept on record but never counted; anything else not completed is skipped.
        val status =
            onlyWhen(Condition("status", ConditionOp.IN, "completed,failed,reverted")) +
                listOf("failed", "reverted").map { excludeWhen("status", "^$it$", reason = it) }
        val incomingTypes = "receive,reward,un_stake"
        val outgoingTypes = "send,stake"

        // The account at the other end of a receive or send, by the type of account its leg names.
        val otherEnd = "counterparty_account"
        val revolutX = "Revolut X"
        val fromRevolutX = Condition("source.account.type", ConditionOp.EQUALS, "revolut_x")
        val toRevolutX = Condition("destination.account.type", ConditionOp.EQUALS, "revolut_x")

        fun negated(condition: Condition) = condition.copy(op = ConditionOp.NOT_EQUALS)

        // Money comes in on the destination leg and goes out on the source one; the other leg names the far end.
        fun ownLeg(
            incoming: Boolean,
            vararg conditions: Condition,
        ): StrategyRule {
            val own = if (incoming) "destination" else "source"
            val far = if (incoming) "source" else "destination"
            return rule(
                RuleAction.SetDirection(incoming = incoming),
                RuleAction.SetValue("amount", RuleValue.read("$own.amount")),
                RuleAction.SetValue("currency", RuleValue.read("$own.currency")),
                RuleAction.SetValue(otherEnd, RuleValue.read("$far.account.type")),
                conditions = conditions.toList(),
            )
        }

        return ApiImportStrategy(
            id = ApiImportStrategyId(STRATEGY_ID),
            name = revolutX,
            config =
                ApiStrategyConfig(
                    baseUrl = "https://revx.revolut.com/api",
                    requestSigning =
                        ApiRequestSigningConfig(
                            algorithm = SigningAlgorithm.ED25519,
                            signatureEncoding = SignatureEncoding.BASE64,
                            // Every request is a GET with no body, so the verb is a literal.
                            message = listOf(SigPart.Nonce, SigPart.Literal("GET"), SigPart.Path, SigPart.QueryString),
                            apiKey = FieldPlacement(SigFieldLocation.HEADER, "X-Revx-API-Key"),
                            nonce = NonceSpec(NonceFormat.EPOCH_MS, FieldPlacement(SigFieldLocation.HEADER, "X-Revx-Timestamp")),
                            signature = FieldPlacement(SigFieldLocation.HEADER, "X-Revx-Signature"),
                            bodyFormat = BodyFormat.NONE,
                        ),
                    accounts = ApiAccountsSource.Single(name = revolutX, externalId = "revolut-x"),
                    dataEndpoints =
                        listOf(
                            ApiDataEndpoint(
                                transactions("buy,sell"),
                                ApiEndpointKind.TRADES,
                                tradeMappings =
                                    ApiTradeMappings(
                                        splitMode = InstrumentSplitMode.EXPLICIT_FIELDS,
                                        baseAssetField = "destination.currency",
                                        quoteAssetField = "source.currency",
                                        baseQuantityField = "destination.amount",
                                        quoteQuantityField = "source.amount",
                                        timestampField = "created_date",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        idField = "id",
                                        rules =
                                            status +
                                                // A sell debits the base asset and credits the quote one.
                                                rule(
                                                    RuleAction.SwapValues("source.currency", "destination.currency"),
                                                    RuleAction.SwapValues("source.amount", "destination.amount"),
                                                    conditions = listOf(type("sell")),
                                                ) +
                                                tradeSideFrom("type", "buy"),
                                    ),
                            ),
                            ApiDataEndpoint(
                                transactions("$incomingTypes,$outgoingTypes"),
                                ApiEndpointKind.DEPOSITS,
                                transactionMappings =
                                    ApiTransactionMappings(
                                        timestampField = "created_date",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                                        descriptionField = "type",
                                        rules =
                                            status +
                                                // The Revolut X leg is whichever leg names a Revolut X account: a
                                                // `send` is any move between the person's own accounts, so it
                                                // goes either way (Revolut app → Revolut X is a `send` too).
                                                ownLeg(incoming = true, toRevolutX) +
                                                ownLeg(incoming = false, fromRevolutX, negated(toRevolutX)) +
                                                // A leg without an account type falls back on the type.
                                                ownLeg(incoming = true, type(incomingTypes), negated(fromRevolutX), negated(toRevolutX)) +
                                                ownLeg(incoming = false, type(outgoingTypes), negated(fromRevolutX), negated(toRevolutX)) +
                                                accounts(
                                                    AccountSide.COUNTERPARTY,
                                                    fixedNaming("type", "Revolut X Staking"),
                                                    conditions = listOf(type("stake,un_stake")),
                                                ) +
                                                accounts(
                                                    AccountSide.COUNTERPARTY,
                                                    fixedNaming("type", "Revolut X Staking Rewards"),
                                                    conditions = listOf(type("reward")),
                                                ) +
                                                accounts(
                                                    AccountSide.COUNTERPARTY,
                                                    // The Revolut app's own account, from which most money arrives.
                                                    naming(otherEnd, pattern = "^revolut$", name = "Revolut"),
                                                    // External bank accounts and crypto wallets: the list doesn't
                                                    // say which one.
                                                    fixedNaming("type", "Revolut X Funding"),
                                                ),
                                    ),
                            ),
                        ),
                    rateLimitMillis = 2_000L,
                    rateLimitErrorSubstrings = listOf("HTTP 429"),
                    tokenPageUrl = "https://exchange.revolut.com/account/api-keys",
                    connectInstructions =
                        listOf(
                            "Make an Ed25519 key pair on your computer: openssl genpkey -algorithm ed25519 -out private.pem, " +
                                "then openssl pkey -in private.pem -pubout -out public.pem.",
                            "On the Revolut X API keys page (sign in if asked), add a new key with the contents of public.pem " +
                                "as its public key. Give it read-only access.",
                            "Copy the 64-character API key and paste it below as the API key.",
                            "Load private.pem below as the private key (or paste its contents).",
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }
}
