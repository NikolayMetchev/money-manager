package com.moneymanager.builtin

import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiAccountBridge
import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAccountNameRule
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiAuthType
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiInternalTransferReconcile
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiPeopleMappings
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiRequestSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiSignSource
import com.moneymanager.domain.model.apistrategy.ApiSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiSyntheticAccount
import com.moneymanager.domain.model.apistrategy.ApiTradeMappings
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.BodyFormat
import com.moneymanager.domain.model.apistrategy.BuiltInCounterpartyRule
import com.moneymanager.domain.model.apistrategy.FieldPlacement
import com.moneymanager.domain.model.apistrategy.HttpMethodType
import com.moneymanager.domain.model.apistrategy.InstrumentSplitMode
import com.moneymanager.domain.model.apistrategy.NonceFormat
import com.moneymanager.domain.model.apistrategy.NonceSpec
import com.moneymanager.domain.model.apistrategy.PaginationMode
import com.moneymanager.domain.model.apistrategy.ParamStringFormat
import com.moneymanager.domain.model.apistrategy.PredicateOp
import com.moneymanager.domain.model.apistrategy.RequestIdSpec
import com.moneymanager.domain.model.apistrategy.RulePredicate
import com.moneymanager.domain.model.apistrategy.RuleSign
import com.moneymanager.domain.model.apistrategy.SecretEncoding
import com.moneymanager.domain.model.apistrategy.SigFieldLocation
import com.moneymanager.domain.model.apistrategy.SigPart
import com.moneymanager.domain.model.apistrategy.SignatureEncoding
import com.moneymanager.domain.model.apistrategy.SigningAlgorithm
import com.moneymanager.domain.model.apistrategy.TimestampFormat
import com.moneymanager.domain.model.apistrategy.WindowBoundFormat
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Built-in API import strategy definitions (Monzo/Wise/Starling). db-free domain objects. */
object BuiltInApiStrategies {
    val monzoStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000001")
    val wiseStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000002")
    val starlingStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000005")
    val cryptoComExchangeStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000009")
    val krakenStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000a")

    /** All built-in API import strategies. */
    fun builtInApiStrategies(now: Instant): List<ApiImportStrategy> =
        listOf(monzo(now), wise(now), starling(now), cryptoComExchange(now), kraken(now))

    /** The built-in Monzo API import strategy. */
    fun monzo(now: Instant): ApiImportStrategy =
        ApiImportStrategy(
            id = ApiImportStrategyId(monzoStrategyId),
            name = "Monzo",
            baseUrl = "https://api.monzo.com",
            authType = ApiAuthType.BEARER_TOKEN,
            accountsEndpoint =
                ApiEndpointConfig(
                    path = "/accounts",
                    responseArrayKey = "accounts",
                ),
            transactionsEndpoint =
                ApiEndpointConfig(
                    path = "/transactions",
                    responseArrayKey = "transactions",
                    queryParams =
                        listOf(
                            ApiQueryParam(name = "account_id", dynamicSource = "account.id"),
                        ),
                    pagination = ApiPaginationConfig(),
                ),
            accountMappings =
                ApiAccountMappings(
                    ownerNameField = "preferred_name",
                    // Monzo's account "description" is the account holder's own user id, not a
                    // display name — Monzo's API has no field meant for this, so use a fixed name
                    // ("Monzo Joint" for a joint account, detected by having more than one owner).
                    staticAccountName = "Monzo",
                    // Monzo's cashback/rewards opt-in is a pseudo-account with no bank details and (for
                    // most users) no activity — it would otherwise collide with the main "Monzo" name.
                    accountNameRules =
                        listOf(
                            ApiAccountNameRule(
                                suffix = "Rewards",
                                predicates = listOf(RulePredicate(path = "type", op = PredicateOp.EQUALS, value = "uk_rewards")),
                            ),
                        ),
                ),
            transactionMappings =
                ApiTransactionMappings(
                    merchantNameField = "merchant.name",
                    counterpartyNameField = "counterparty.name",
                    counterpartyIdField = "counterparty.id",
                    declineReasonField = "decline_reason",
                    localAmountField = "local_amount",
                    localCurrencyField = "local_currency",
                    // Foreign ATM withdrawals above the fee-free allowance carry a charge in
                    // `atm_fees_detailed.fee_amount` (integer minor units; null/0 otherwise). Import it
                    // as its own linked fee transfer. Monzo's `amount` is gross (= withdrawal_amount +
                    // fee_amount), so the fee is carved out of the main transfer rather than added on top.
                    feeAmountField = "atm_fees_detailed.fee_amount",
                    feeIncludedInAmount = true,
                ),
            // Monzo issues a throwaway `anonuser_…` user id for every bank transfer, so the same person
            // would otherwise become one counterparty account per transaction. Mark the prefix ephemeral
            // so such no-bank counterparties are matched by name instead.
            peopleMappings = ApiPeopleMappings(ephemeralCounterpartyIdPrefixes = setOf("anonuser_")),
            builtInCounterpartyRules = monzoAtmRules,
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
            createdAt = now,
            updatedAt = now,
        )

    /**
     * Monzo ATM-detection expressed declaratively (moved out of the import engine). Each rule routes
     * matching outgoing transactions to a single consolidated "ATM" counterparty account.
     */
    private val monzoAtmRules: List<BuiltInCounterpartyRule> =
        listOf(
            BuiltInCounterpartyRule(
                name = "ATM",
                onlyWhenSign = RuleSign.NEGATIVE,
                predicates = listOf(RulePredicate(path = "atm_fees_detailed", op = PredicateOp.EXISTS)),
            ),
            BuiltInCounterpartyRule(
                name = "ATM",
                onlyWhenSign = RuleSign.NEGATIVE,
                predicates = listOf(RulePredicate(path = "labels", op = PredicateOp.ARRAY_ANY_STARTS_WITH, value = "withdrawal.atm")),
            ),
            BuiltInCounterpartyRule(
                name = "ATM",
                onlyWhenSign = RuleSign.NEGATIVE,
                predicates = listOf(RulePredicate(path = "metadata.mcc", op = PredicateOp.EQUALS, value = "6011")),
            ),
            BuiltInCounterpartyRule(
                name = "ATM",
                onlyWhenSign = RuleSign.NEGATIVE,
                predicates =
                    listOf(
                        RulePredicate(path = "category", op = PredicateOp.EQUALS_IGNORE_CASE, value = "cash"),
                        RulePredicate(path = "merchant", op = PredicateOp.OBJECT_EMPTY),
                        RulePredicate(path = "counterparty", op = PredicateOp.OBJECT_EMPTY),
                    ),
            ),
        )

    /**
     * The built-in Wise API import strategy. Wise has a three-level hierarchy
     * (profiles → balances → statements): profiles are fetched as an ancestor endpoint and their id
     * is templated into the balances and statement paths; amounts are decimal with the direction in
     * a separate `type` field; statements are fetched in date windows.
     */
    fun wise(now: Instant): ApiImportStrategy =
        ApiImportStrategy(
            id = ApiImportStrategyId(wiseStrategyId),
            name = "Wise",
            baseUrl = "https://api.wise.com",
            authType = ApiAuthType.BEARER_TOKEN,
            ancestorEndpoints =
                listOf(
                    ApiEndpointConfig(path = "/v1/profiles", responseArrayKey = ""),
                ),
            accountsEndpoint =
                ApiEndpointConfig(
                    path = "/v4/profiles/{ancestor[0].id}/balances",
                    responseArrayKey = "",
                    queryParams = listOf(ApiQueryParam(name = "types", value = "STANDARD")),
                ),
            transactionsEndpoint =
                ApiEndpointConfig(
                    path = "/v1/profiles/{ancestor[0].id}/balance-statements/{account.id}/statement.json",
                    responseArrayKey = "transactions",
                    queryParams =
                        listOf(
                            ApiQueryParam(name = "currency", dynamicSource = "account.currency"),
                            ApiQueryParam(name = "type", value = "FLAT"),
                        ),
                    // startParam/endParam/windowDays default to Wise's values (intervalStart,
                    // intervalEnd, 469).
                    pagination = ApiPaginationConfig(mode = PaginationMode.DATE_WINDOW),
                ),
            accountMappings =
                ApiAccountMappings(
                    // Balances only have a "name" when the user explicitly names them (null by
                    // default), which made account names fall back to the opaque balance id.
                    // Use the currency code instead so accounts are named "Wise: EUR" etc.,
                    // matching the accounts the built-in Wise CSV strategy resolves per-row.
                    descriptionField = "currency",
                    ownersArrayField = null,
                    currencyField = "currency",
                ),
            transactionMappings =
                ApiTransactionMappings(
                    amountField = "amount.value",
                    currencyField = "amount.currency",
                    timestampField = "date",
                    descriptionField = "details.description",
                    amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                    signSource = ApiSignSource.FIELD,
                    signField = "type",
                    creditValues = setOf("CREDIT"),
                    idField = "referenceNumber",
                    merchantNameField = "details.merchant.name",
                    counterpartyNameField = "details.senderName",
                ),
            // Wise balance statements are SCA-protected: a 403 returns an x-2fa-approval one-time
            // token that must be signed with the credential's private key and replayed. Statements
            // are only available via the API for accounts based in these countries.
            signing =
                ApiSigningConfig(
                    statementCountries = setOf("US", "CA", "AU", "NZ", "SG", "MY"),
                ),
            // The account holder lives on the profile (the ancestor), not the balance, so people
            // are downloaded/imported separately from the /v1/profiles endpoint.
            peopleDownload =
                ApiPersonImportConfig(
                    endpoint = ApiEndpointConfig(path = "/v1/profiles", responseArrayKey = ""),
                    firstNameField = "details.firstName",
                    lastNameField = "details.lastName",
                    preferredNameField = "details.preferredName",
                    fallbackNameField = "details.name",
                    accountOwnerAncestorExpr = "ancestor[0].id",
                ),
            personExternalIdAttribute = "wise-external-id",
            tokenPageUrl = "https://wise.com/your-account/integrations-and-tools/api-tokens",
            connectInstructions =
                listOf(
                    "Open the Wise API tokens page in your browser and sign in.",
                    "Create a new API token (read access is sufficient) and copy it.",
                    "Paste the token below and save.",
                    "Statements are protected by Strong Customer Authentication: generate a signing key below and " +
                        "register its public key in Wise (Settings → API tokens → Manage public keys).",
                    "Note: retrieving statements via the API is only supported for accounts based in the US, Canada, " +
                        "Australia, New Zealand, Singapore, and Malaysia.",
                ),
            createdAt = now,
            updatedAt = now,
        )

    /**
     * The built-in Starling Bank API import strategy. Starling is a flat two-level API like
     * Monzo: a single Bearer token lists accounts, and each account's transaction feed is fetched
     * from a path templated with the account id and its `defaultCategory`. Amounts are integer minor
     * units and the direction lives in a separate `direction` field (IN/OUT). The whole feed is
     * returned in one response, so no pagination is configured. The account holder is a single global
     * object (`/api/v2/account-holder/individual`) linked to every imported account.
     */
    fun starling(now: Instant): ApiImportStrategy =
        ApiImportStrategy(
            id = ApiImportStrategyId(starlingStrategyId),
            name = "Starling",
            baseUrl = "https://api.starlingbank.com",
            authType = ApiAuthType.BEARER_TOKEN,
            accountsEndpoint =
                ApiEndpointConfig(
                    path = "/api/v2/accounts",
                    responseArrayKey = "accounts",
                ),
            transactionsEndpoint =
                ApiEndpointConfig(
                    // {account.defaultCategory} resolves the account's defaultCategory from its raw
                    // JSON; the feed endpoint returns the full history in a single response.
                    path = "/api/v2/feed/account/{account.id}/category/{account.defaultCategory}",
                    responseArrayKey = "feedItems",
                    // Starling requires a mandatory `changesSince` ISO-8601 bound; anchoring it to
                    // the epoch returns the account's entire feed history in one response.
                    queryParams =
                        listOf(
                            ApiQueryParam(name = "changesSince", value = "1970-01-01T00:00:00.000Z"),
                        ),
                ),
            accountMappings =
                ApiAccountMappings(
                    idField = "accountUid",
                    descriptionField = "name",
                    currencyField = "currency",
                    ownersArrayField = null,
                    // Starling's /accounts response omits bank details; they come from the
                    // account-identifiers endpoint below, where the sort code is `bankIdentifier`
                    // and the account number is `accountIdentifier`.
                    sortCodeField = "bankIdentifier",
                    accountNumberField = "accountIdentifier",
                ),
            // Per-account endpoint that returns the account's own sort code + account number, so the
            // source account can be matched/merged with counterparties other providers create for it.
            accountIdentifiersEndpoint =
                ApiEndpointConfig(
                    path = "/api/v2/accounts/{account.id}/identifiers",
                    responseArrayKey = "",
                ),
            transactionMappings =
                ApiTransactionMappings(
                    amountField = "amount.minorUnits",
                    currencyField = "amount.currency",
                    timestampField = "transactionTime",
                    descriptionField = "reference",
                    amountFormat = ApiAmountFormat.MINOR_UNITS_INTEGER,
                    signSource = ApiSignSource.FIELD,
                    signField = "direction",
                    creditValues = setOf("IN"),
                    idField = "feedItemUid",
                    counterpartyNameField = "counterPartyName",
                    // counterPartyUid is the fallback counterparty-account id; bank details
                    // (sub-entity sort code + account number) take precedence where present, see
                    // peopleMappings.preferBankIdentity. A single real account can otherwise be
                    // split across uids (e.g. the same account as both a payee and a sender).
                    counterpartyIdField = "counterPartyUid",
                    // Declined feed items never moved money; import them but exclude from balances
                    // (same treatment as Monzo's `decline_reason`), keyed off Starling's status.
                    declineStatusField = "status",
                    declinedStatusValues = setOf("DECLINED"),
                    // Persist the feed item's stable id as a transaction attribute so each imported
                    // transfer is uniquely identifiable and re-imports dedupe on it.
                    customFields = mapOf("starling-transaction-id" to "feedItemUid"),
                    uniqueIdentifierFields = setOf("starling-transaction-id"),
                ),
            // Starling's counterparty fields are flat on the feed item (no nested object), so the
            // counterparty object path is blank (the item itself). PAYEE/SENDER counterparties are
            // people; MERCHANT/STARLING are not. counterPartyUid identifies the person.
            peopleMappings =
                ApiPeopleMappings(
                    counterpartyObjectField = "",
                    beneficiaryAccountTypeField = "counterPartyType",
                    personalBeneficiaryAccountTypeValues = setOf("PAYEE", "SENDER"),
                    counterpartyNameField = "counterPartyName",
                    counterpartyUserIdField = "counterPartyUid",
                    // Starling exposes the counterparty's bank details flat on the feed item;
                    // together they uniquely identify the account and take precedence over the
                    // per-counterparty uid when de-duplicating counterparty accounts.
                    counterpartySortCodeField = "counterPartySubEntityIdentifier",
                    counterpartyAccountNumberField = "counterPartySubEntitySubIdentifier",
                    preferBankIdentity = true,
                ),
            // The account holder is global (one per connection) and returned as a single object,
            // so it is linked to every account imported in the session.
            peopleDownload =
                ApiPersonImportConfig(
                    endpoint = ApiEndpointConfig(path = "/api/v2/account-holder/individual", responseArrayKey = ""),
                    firstNameField = "firstName",
                    lastNameField = "lastName",
                    ownsAllAccounts = true,
                ),
            personExternalIdAttribute = "starling-external-id",
            tokenPageUrl = "https://developer.starlingbank.com/",
            connectInstructions =
                listOf(
                    "Open the Starling Developer portal in your browser and sign in with your Starling account.",
                    "Create a personal access token with the account:read, transaction:read and " +
                        "account-holder-name:read scopes.",
                    "Copy the token, paste it below and save.",
                ),
            createdAt = now,
            updatedAt = now,
        )

    /**
     * Built-in Crypto.com **Exchange** API strategy — pure config over the generic signed-exchange
     * engine (no provider code). Uses HMAC-SHA256 request signing, a single "Crypto.com Exchange"
     * account holding all assets, and data endpoints for trades, order history and fiat/crypto
     * deposits/withdrawals. Internal transfers reconcile against the CSV "Crypto.com" App account.
     *
     * Field paths follow the Crypto.com Exchange v1 REST docs; verify against a live response when
     * connecting real keys (the shapes are covered by the db-level E2E test).
     */
    fun cryptoComExchange(now: Instant): ApiImportStrategy {
        val unused = ApiEndpointConfig(path = "unused", responseArrayKey = "")
        // get-deposit-history / get-withdrawal-history serve years of history, so page a long lookback.
        val historyWindow =
            ApiPaginationConfig(
                mode = PaginationMode.DATE_WINDOW,
                startParam = "start_ts",
                endParam = "end_ts",
                windowDays = 90,
            )
        // get-trades / get-order-history only serve the last 6 months (older trades come from the CSV
        // import) and cap the window at 7 days; unlike the deposit/withdrawal endpoints they take
        // start_time/end_time (start_ts/end_ts is silently ignored and defaults to the last 24h).
        val recentWindow =
            historyWindow.copy(
                startParam = "start_time",
                endParam = "end_time",
                windowDays = 7,
                lookbackDays = 180,
            )

        fun signed(
            path: String,
            key: String,
            pagination: ApiPaginationConfig,
        ) = ApiEndpointConfig(
            path = path,
            responseArrayKey = key,
            method = HttpMethodType.POST,
            successCodeField = "code",
            successCodeOkValue = "0",
            pagination = pagination,
        )
        val tradeMappings =
            ApiTradeMappings(
                instrumentField = "instrument_name",
                sideField = "side",
                buyValues = setOf("BUY", "buy"),
                baseQuantityField = "traded_quantity",
                priceField = "traded_price",
                feeField = "fees",
                feeCurrencyField = "fee_instrument_name",
                timestampField = "create_time",
                timestampFormat = TimestampFormat.EPOCH_MS,
                idField = "trade_id",
                orderIdField = "order_id",
            )
        val orderMappings =
            ApiTradeMappings(
                instrumentField = "instrument_name",
                sideField = "side",
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
                // An internal transfer (funds moved from/to the Crypto.com App) is booked directly against
                // the "Crypto.com" App account so the same movement in the CSV export reconciles to it.
                counterpartyAliasField = "address",
                counterpartyAccountAliases = mapOf("INTERNAL_DEPOSIT" to "Crypto.com", "INTERNAL_WITHDRAWAL" to "Crypto.com"),
            )
        return ApiImportStrategy(
            id = ApiImportStrategyId(cryptoComExchangeStrategyId),
            name = "Crypto.com Exchange",
            baseUrl = "https://api.crypto.com/exchange/v1",
            authType = ApiAuthType.SIGNED,
            accountsEndpoint = unused,
            transactionsEndpoint = unused,
            accountMappings = ApiAccountMappings(),
            transactionMappings = ApiTransactionMappings(),
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
            syntheticAccount = ApiSyntheticAccount(name = "Crypto.com Exchange", externalId = "crypto-com-exchange"),
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
                    windowSeconds = 24 * 3600,
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
            createdAt = now,
            updatedAt = now,
        )
    }

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
    fun kraken(now: Instant): ApiImportStrategy {
        val unused = ApiEndpointConfig(path = "unused", responseArrayKey = "")
        // Both TradesHistory and Ledgers page the same way: a date window (Kraken's start/end are whole
        // seconds, not millis) further paged by an offset ("ofs") in `limitValue`-sized chunks, bounded
        // by the response's total `result.count`.
        val historyWindow =
            ApiPaginationConfig(
                mode = PaginationMode.DATE_WINDOW,
                startParam = "start",
                endParam = "end",
                windowBoundFormat = WindowBoundFormat.EPOCH_S,
                windowDays = 90,
                offsetParam = "ofs",
                limitValue = 50,
                totalCountField = "result.count",
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

        // Kraken's legacy asset codes, normalized to their canonical ISO/ticker form before any
        // currency/crypto lookup.
        val assetAliasMap =
            mapOf(
                "XXBT" to "BTC",
                "XBT" to "BTC",
                "XETH" to "ETH",
                "XXRP" to "XRP",
                "XLTC" to "LTC",
                "XXLM" to "XLM",
                "XXMR" to "XMR",
                "XZEC" to "ZEC",
                "XETC" to "ETC",
                "XREP" to "REP",
                "XXDG" to "DOGE",
                "XDG" to "DOGE",
                "XMLN" to "MLN",
                "ZUSD" to "USD",
                "ZEUR" to "EUR",
                "ZGBP" to "GBP",
                "ZCAD" to "CAD",
                "ZJPY" to "JPY",
                "ZCHF" to "CHF",
                "ZAUD" to "AUD",
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
                sideField = "type",
                buyValues = setOf("buy"),
                baseQuantityField = "vol",
                quoteQuantityField = "cost",
                // No feeField: TradesHistory's own fee is a quote-currency report that can disagree with
                // (or duplicate) what was actually charged — sometimes in a different asset entirely (a
                // base-asset settlement fee Kraken bills separately). The Ledgers `type=all` feed already
                // supplies every fee authoritatively (see ledgerMappings' feeAmountField), so the trade
                // path books none of its own.
                timestampField = "time",
                timestampFormat = TimestampFormat.EPOCH_S_FLOAT,
                idField = "trade_id",
                orderIdField = "ordertxid",
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
                feeAmountField = "fee",
                joinKeyField = joinKey,
                // A failed/cancelled deposit or withdrawal appears as two ledger rows sharing one refid
                // with opposite-signed amounts (the debit and its reversal), netting to zero. Kraken's
                // "amount" is signed (negative = out), so trust that sign instead of the endpoint's fixed
                // direction, or the reversal double-books as a second real movement in the same direction.
                directionFromAmountSign = true,
                // Only meaningful on the excluded `type=trade` rows (see excludeField/excludeValues
                // below) — refid equals the matching TradesHistory trade's own id.
                reconcileTradeAmountsField = "refid",
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
            id = ApiImportStrategyId(krakenStrategyId),
            name = "Kraken",
            baseUrl = "https://api.kraken.com",
            authType = ApiAuthType.SIGNED,
            accountsEndpoint = unused,
            transactionsEndpoint = unused,
            accountMappings = ApiAccountMappings(),
            transactionMappings = ApiTransactionMappings(),
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
            syntheticAccount = ApiSyntheticAccount(name = "Kraken", externalId = "kraken"),
            dataEndpoints =
                listOf(
                    ApiDataEndpoint(
                        // Splice the response object's own key (e.g. "STVCTCR-ERSZJ-HBXQB2") over the
                        // "trade_id" field before mapping: the native `trade_id` is a small per-fill
                        // sequence number that collides across unrelated trades (observed repeatedly as
                        // 0), whereas the key is unique and — critically — is the same identifier
                        // Kraken's Ledgers rows carry as `refid`, letting reconcileTradeAmountsField join
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
                    // entries that duplicate what TradesHistory already supplies, so those are dropped via
                    // excludeField/excludeValues. Direction comes from the signed `amount` field
                    // (directionFromAmountSign), not the ledger `type`, so this single endpoint covers
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
                        transactionMappings = ledgerMappings("refid").copy(excludeField = "type", excludeValues = setOf("trade")),
                    ),
                    // Known limitation: Kraken paginates these funding-status endpoints with an opaque
                    // cursor token (not the offset/date-window shapes the generic engine implements), so
                    // only the first page is fetched here — enrichment (on-chain address/txid) beyond
                    // that page is silently skipped, though the underlying deposit/withdrawal transfer
                    // itself (from Ledgers, above) is unaffected. Extending PaginationMode.CURSOR to the
                    // exchange engine to cover this needs the real cursor field verified against a live
                    // response before it's worth adding.
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
            assetAliases = assetAliasMap,
            // Kraken Earn holdings use a suffixed asset code for the same underlying asset (e.g. the
            // "Flexible Earn" ETH position is "XETH.F", staked is "XETH.S"); strip it so the position's
            // deposit/withdrawal ledger entries resolve to the ordinary "ETH" asset like any other.
            assetSuffixesToStrip = setOf(".F", ".S", ".M"),
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
            createdAt = now,
            updatedAt = now,
        )
    }
}
