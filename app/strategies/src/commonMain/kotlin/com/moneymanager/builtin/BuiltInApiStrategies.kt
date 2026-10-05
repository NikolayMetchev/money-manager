package com.moneymanager.builtin

import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiAccountBridge
import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAccountNameRule
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiDateWindowing
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiFanOut
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiInternalTransferReconcile
import com.moneymanager.domain.model.apistrategy.ApiLedgerTrades
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiPaging
import com.moneymanager.domain.model.apistrategy.ApiPeopleMappings
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiRequestSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiServerTimeSync
import com.moneymanager.domain.model.apistrategy.ApiSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.ApiTokenExchange
import com.moneymanager.domain.model.apistrategy.ApiTradeMappings
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.ApiValueSet
import com.moneymanager.domain.model.apistrategy.BodyFormat
import com.moneymanager.domain.model.apistrategy.BuiltInCounterpartyRule
import com.moneymanager.domain.model.apistrategy.FieldPlacement
import com.moneymanager.domain.model.apistrategy.HttpMethodType
import com.moneymanager.domain.model.apistrategy.InstrumentSplitMode
import com.moneymanager.domain.model.apistrategy.JwtAlgorithm
import com.moneymanager.domain.model.apistrategy.JwtField
import com.moneymanager.domain.model.apistrategy.JwtSigningConfig
import com.moneymanager.domain.model.apistrategy.NonceFormat
import com.moneymanager.domain.model.apistrategy.NonceSpec
import com.moneymanager.domain.model.apistrategy.ParamStringFormat
import com.moneymanager.domain.model.apistrategy.RequestIdSpec
import com.moneymanager.domain.model.apistrategy.RuleSign
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
import com.moneymanager.domain.model.rules.Direction
import com.moneymanager.domain.model.rules.Extraction
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Built-in API import strategy definitions. db-free domain objects. */
object BuiltInApiStrategies {
    val monzoStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000001")
    val wiseStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000002")
    val starlingStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000005")
    val cryptoComExchangeStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000009")
    val krakenStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000a")
    val binanceStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000b")
    val coinbaseStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000c")
    val bybitStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000d")
    val payPalStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000e")

    /** All built-in API import strategies. */
    fun builtInApiStrategies(now: Instant): List<ApiImportStrategy> =
        listOf(
            monzo(now),
            wise(now),
            starling(now),
            cryptoComExchange(now),
            kraken(now),
            binance(now),
            coinbase(now),
            bybit(now),
            payPal(now),
        )

    /** The built-in Monzo API import strategy. */
    fun monzo(now: Instant): ApiImportStrategy =
        ApiImportStrategy(
            id = ApiImportStrategyId(monzoStrategyId),
            name = "Monzo",
            config =
                ApiStrategyConfig(
                    baseUrl = "https://api.monzo.com",
                    accounts =
                        ApiAccountsSource.Downloaded(
                            endpoint =
                                ApiEndpointConfig(
                                    path = "/accounts",
                                    responseArrayKey = "accounts",
                                ),
                            mappings =
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
                                                predicates = listOf(Condition("type", ConditionOp.EQUALS, value = "uk_rewards")),
                                            ),
                                        ),
                                ),
                        ),
                    dataEndpoints =
                        listOf(
                            ApiDataEndpoint(
                                endpoint =
                                    ApiEndpointConfig(
                                        path = "/transactions",
                                        responseArrayKey = "transactions",
                                        queryParams =
                                            listOf(
                                                ApiQueryParam(name = "account_id", dynamicSource = "account.id"),
                                            ),
                                        pagination = ApiPaginationConfig(paging = ApiPaging.BeforeCursor(), sendLimitParam = true),
                                    ),
                                kind = ApiEndpointKind.BANK_TRANSACTIONS,
                                transactionMappings =
                                    ApiTransactionMappings(
                                        merchantNameField = "merchant.name",
                                        counterpartyNameField = "counterparty.name",
                                        counterpartyIdField = "counterparty.id",
                                        declinedWhen = listOf(Condition("decline_reason", ConditionOp.NOT_BLANK)),
                                        localAmountField = "local_amount",
                                        localCurrencyField = "local_currency",
                                        // Foreign ATM withdrawals above the fee-free allowance carry a charge in
                                        // `atm_fees_detailed.fee_amount` (integer minor units; null/0 otherwise). Import it
                                        // as its own linked fee transfer. Monzo's `amount` is gross (= withdrawal_amount +
                                        // fee_amount), so the fee is carved out of the main transfer rather than added on top.
                                        fee = FeeRule(amount = ValueExpr(listOf("atm_fees_detailed.fee_amount")), includedInAmount = true),
                                    ),
                            ),
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
                ),
            createdAt = now,
            updatedAt = now,
        )

    /**
     * The built-in PayPal API import strategy (Transaction Search). The credential is a REST app's client
     * id + secret, exchanged for a bearer token before every download. Each currency balance becomes its
     * own account; a balance carries nothing but its `currency`, so its stored id is prefixed to keep it
     * from matching another provider's account. Amounts are signed decimals with the fee (negative) beside
     * the gross amount, and history is fetched in PayPal's maximum 31-day windows.
     */
    fun payPal(now: Instant): ApiImportStrategy =
        ApiImportStrategy(
            id = ApiImportStrategyId(payPalStrategyId),
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
                                    currencyField = "currency",
                                    idExtraction = Extraction(pattern = "^(.+)$", outputTemplate = "paypal:$1"),
                                    descriptionExtraction = Extraction(pattern = "^(.+)$", outputTemplate = "PayPal $1"),
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
                                        direction = Direction.AmountSign(),
                                        counterpartyNameField = "payer_info.payer_name.alternate_full_name",
                                        declinedWhen =
                                            listOf(
                                                Condition("transaction_info.transaction_status", ConditionOp.EQUALS, value = "D"),
                                            ),
                                        // The amount is gross; PayPal's fee (negative) comes off on top of it.
                                        fee =
                                            FeeRule(
                                                amount = ValueExpr.of("transaction_info.fee_amount.value"),
                                                currency = ValueExpr.of("transaction_info.fee_amount.currency_code"),
                                            ),
                                        customFields =
                                            mapOf(
                                                "paypal-event-code" to "transaction_info.transaction_event_code",
                                                "paypal-payer-email" to "payer_info.email_address",
                                            ),
                                    ),
                            ),
                        ),
                    // Event codes T02xx are currency conversions: one item per currency, both routed through
                    // one account so the pair nets out.
                    builtInCounterpartyRules =
                        listOf(
                            BuiltInCounterpartyRule(
                                name = "PayPal Currency Conversion",
                                predicates =
                                    listOf(Condition("transaction_info.transaction_event_code", ConditionOp.STARTS_WITH, value = "T02")),
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

    /**
     * Monzo ATM-detection expressed declaratively (moved out of the import engine). Each rule routes
     * matching outgoing transactions to a single consolidated "ATM" counterparty account.
     */
    private val monzoAtmRules: List<BuiltInCounterpartyRule> =
        listOf(
            BuiltInCounterpartyRule(
                name = "ATM",
                onlyWhenSign = RuleSign.NEGATIVE,
                predicates = listOf(Condition("atm_fees_detailed", ConditionOp.EXISTS)),
            ),
            BuiltInCounterpartyRule(
                name = "ATM",
                onlyWhenSign = RuleSign.NEGATIVE,
                predicates = listOf(Condition("labels", ConditionOp.ANY_ELEMENT_STARTS_WITH, value = "withdrawal.atm")),
            ),
            BuiltInCounterpartyRule(
                name = "ATM",
                onlyWhenSign = RuleSign.NEGATIVE,
                predicates = listOf(Condition("metadata.mcc", ConditionOp.EQUALS, value = "6011")),
            ),
            BuiltInCounterpartyRule(
                name = "ATM",
                onlyWhenSign = RuleSign.NEGATIVE,
                predicates =
                    listOf(
                        Condition("category", ConditionOp.EQUALS_IGNORE_CASE, value = "cash"),
                        Condition("merchant", ConditionOp.EMPTY_OBJECT),
                        Condition("counterparty", ConditionOp.EMPTY_OBJECT),
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
            config =
                ApiStrategyConfig(
                    baseUrl = "https://api.wise.com",
                    accounts =
                        ApiAccountsSource.Downloaded(
                            endpoint =
                                ApiEndpointConfig(
                                    path = "/v4/profiles/{ancestor[0].id}/balances",
                                    responseArrayKey = "",
                                    queryParams = listOf(ApiQueryParam(name = "types", value = "STANDARD")),
                                ),
                            mappings =
                                ApiAccountMappings(
                                    // Balances only have a "name" when the user explicitly names them (null by
                                    // default), which made account names fall back to the opaque balance id.
                                    // Use the currency code instead so accounts are named "Wise: EUR" etc.,
                                    // matching the accounts the built-in Wise CSV strategy resolves per-row.
                                    descriptionField = "currency",
                                    ownersArrayField = null,
                                    currencyField = "currency",
                                ),
                            ancestorEndpoints =
                                listOf(
                                    ApiEndpointConfig(path = "/v1/profiles", responseArrayKey = ""),
                                ),
                        ),
                    dataEndpoints =
                        listOf(
                            ApiDataEndpoint(
                                endpoint =
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
                                        pagination =
                                            ApiPaginationConfig(
                                                window = ApiDateWindowing(boundFormat = WindowBoundFormat.ISO_8601),
                                            ),
                                    ),
                                kind = ApiEndpointKind.BANK_TRANSACTIONS,
                                transactionMappings =
                                    ApiTransactionMappings(
                                        amountField = "amount.value",
                                        currencyField = "amount.currency",
                                        timestampField = "date",
                                        descriptionField = "details.description",
                                        amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                                        direction = Direction.Field(path = "type", incomingValues = setOf("CREDIT")),
                                        idField = "referenceNumber",
                                        merchantNameField = "details.merchant.name",
                                        counterpartyNameField = "details.senderName",
                                    ),
                            ),
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
            config =
                ApiStrategyConfig(
                    baseUrl = "https://api.starlingbank.com",
                    accounts =
                        ApiAccountsSource.Downloaded(
                            endpoint =
                                ApiEndpointConfig(
                                    path = "/api/v2/accounts",
                                    responseArrayKey = "accounts",
                                ),
                            mappings =
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
                            identifiersEndpoint =
                                ApiEndpointConfig(
                                    path = "/api/v2/accounts/{account.id}/identifiers",
                                    responseArrayKey = "",
                                ),
                        ),
                    dataEndpoints =
                        listOf(
                            ApiDataEndpoint(
                                endpoint =
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
                                kind = ApiEndpointKind.BANK_TRANSACTIONS,
                                transactionMappings =
                                    ApiTransactionMappings(
                                        amountField = "amount.minorUnits",
                                        currencyField = "amount.currency",
                                        timestampField = "transactionTime",
                                        descriptionField = "reference",
                                        amountFormat = ApiAmountFormat.MINOR_UNITS_INTEGER,
                                        direction = Direction.Field(path = "direction", incomingValues = setOf("IN")),
                                        idField = "feedItemUid",
                                        counterpartyNameField = "counterPartyName",
                                        // counterPartyUid is the fallback counterparty-account id; bank details
                                        // (sub-entity sort code + account number) take precedence where present, see
                                        // peopleMappings.preferBankIdentity. A single real account can otherwise be
                                        // split across uids (e.g. the same account as both a payee and a sender).
                                        counterpartyIdField = "counterPartyUid",
                                        // Declined feed items never moved money; import them but exclude from balances
                                        // (same treatment as Monzo's `decline_reason`), keyed off Starling's status.
                                        declinedWhen = listOf(Condition("status", ConditionOp.IN, value = "DECLINED")),
                                        // Persist the feed item's stable id as a transaction attribute so each imported
                                        // transfer is uniquely identifiable and re-imports dedupe on it.
                                        customFields = mapOf("starling-transaction-id" to "feedItemUid"),
                                        uniqueIdentifierFields = setOf("starling-transaction-id"),
                                    ),
                            ),
                        ),
                    // Per-account endpoint that returns the account's own sort code + account number, so the
                    // source account can be matched/merged with counterparties other providers create for it.
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
                fee = FeeRule(amount = ValueExpr(listOf("fees")), currency = ValueExpr(listOf("fee_instrument_name"))),
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
                // No fee: TradesHistory's own fee is a quote-currency report that can disagree with
                // (or duplicate) what was actually charged — sometimes in a different asset entirely (a
                // base-asset settlement fee Kraken bills separately). The Ledgers `type=all` feed already
                // supplies every fee authoritatively (see ledgerMappings' fee), so the trade
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
                fee = FeeRule(amount = ValueExpr(listOf("fee"))),
                joinKeyField = joinKey,
                // A failed/cancelled deposit or withdrawal appears as two ledger rows sharing one refid
                // with opposite-signed amounts (the debit and its reversal), netting to zero. Kraken's
                // "amount" is signed (negative = out), so trust that sign instead of the endpoint's fixed
                // direction, or the reversal double-books as a second real movement in the same direction.
                direction = Direction.AmountSign(),
                // Only meaningful on the excluded `type=trade` rows (see excludeWhen
                // below) — refid equals the matching TradesHistory trade's own id.
                ledgerTrades = ApiLedgerTrades(key = ValueExpr(listOf("refid"))),
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
                            // entries that duplicate what TradesHistory already supplies, so those are dropped via
                            // excludeWhen. Direction comes from the signed `amount` field
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
                                transactionMappings =
                                    ledgerMappings(
                                        "refid",
                                    ).copy(excludeWhen = listOf(Condition("type", ConditionOp.IN, value = "trade"))),
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
                    assetCodes = AssetCodeRules(aliases = assetAliasMap, stripSuffixes = setOf(".F", ".S", ".M")),
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
    fun binance(now: Instant): ApiImportStrategy {
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
                itemFilters = listOf(Condition("status", ConditionOp.EQUALS, value = "CONFIRMED")),
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
            id = ApiImportStrategyId(binanceStrategyId),
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
                                        itemFilters = listOf(Condition("status", ConditionOp.EQUALS, value = "1")),
                                    ),
                                counterpartyAccountName = "Binance Funding",
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
                                        // "amount" is net of the fee - transactionFee is booked separately.
                                        fee = FeeRule(amount = ValueExpr(listOf("transactionFee"))),
                                        // status 6 = completed (see the capital/withdraw/history docs).
                                        itemFilters = listOf(Condition("status", ConditionOp.EQUALS, value = "6")),
                                    ),
                                counterpartyAccountName = "Binance Funding",
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
                                        itemFilters = listOf(Condition("status", ConditionOp.EQUALS, value = "Successful")),
                                    ),
                                counterpartyAccountName = "Binance Bank",
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
                                        fee = FeeRule(amount = ValueExpr(listOf("totalFee"))),
                                        itemFilters = listOf(Condition("status", ConditionOp.EQUALS, value = "Successful")),
                                    ),
                                counterpartyAccountName = "Binance Bank",
                            ),
                            ApiDataEndpoint(
                                fiatBuyEndpoint,
                                ApiEndpointKind.TRADES,
                                tradeMappings =
                                    ApiTradeMappings(
                                        splitMode = InstrumentSplitMode.EXPLICIT_FIELDS,
                                        baseAssetField = "cryptoCurrency",
                                        quoteAssetField = "fiatCurrency",
                                        fixedSideBuy = true,
                                        // Buying crypto with fiat: obtainAmount = crypto received (base),
                                        // sourceAmount = fiat spent (quote).
                                        baseQuantityField = "obtainAmount",
                                        quoteQuantityField = "sourceAmount",
                                        fee = FeeRule(amount = ValueExpr(listOf("totalFee")), currency = ValueExpr(listOf("fiatCurrency"))),
                                        timestampField = "createTime",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        idField = "orderNo",
                                        itemFilters = listOf(Condition("status", ConditionOp.EQUALS, value = "Completed")),
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
                                        fixedSideBuy = false,
                                        // Selling crypto for fiat: sourceAmount = crypto given (base),
                                        // obtainAmount = fiat received (quote).
                                        baseQuantityField = "sourceAmount",
                                        quoteQuantityField = "obtainAmount",
                                        fee = FeeRule(amount = ValueExpr(listOf("totalFee")), currency = ValueExpr(listOf("fiatCurrency"))),
                                        timestampField = "createTime",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        idField = "orderNo",
                                        itemFilters = listOf(Condition("status", ConditionOp.EQUALS, value = "Completed")),
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
                                        fixedSideBuy = true,
                                        baseQuantityField = "toAmount",
                                        quoteQuantityField = "fromAmount",
                                        timestampField = "createTime",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        idField = "orderId",
                                        itemFilters =
                                            listOf(
                                                Condition("orderStatus", ConditionOp.EQUALS, value = "SUCCESS"),
                                            ),
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
                                        itemFilters = listOf(Condition("status", ConditionOp.EQUALS, value = "SUCCESS")),
                                    ),
                                counterpartyAccountName = binanceEarnAccount,
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
                                        itemFilters = listOf(Condition("status", ConditionOp.EQUALS, value = "PAID")),
                                    ),
                                counterpartyAccountName = binanceEarnAccount,
                            ),
                            ApiDataEndpoint(
                                spotToFundingEndpoint,
                                ApiEndpointKind.WITHDRAWALS,
                                transactionMappings = universalTransferMappings,
                                counterpartyAccountName = binanceFundingWalletAccount,
                            ),
                            ApiDataEndpoint(
                                fundingToSpotEndpoint,
                                ApiEndpointKind.DEPOSITS,
                                transactionMappings = universalTransferMappings,
                                counterpartyAccountName = binanceFundingWalletAccount,
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
                                    ),
                                counterpartyAccountName = "Binance Distribution",
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
                                        fixedBaseAsset = "BNB",
                                        quoteAssetField = "fromAsset",
                                        fixedSideBuy = true,
                                        baseQuantityField = "transferedAmount",
                                        quoteQuantityField = "amount",
                                        timestampField = "operateTime",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        idField = "transId",
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
                                        sideField = "isBuyer",
                                        buyValues = setOf("true"),
                                        baseQuantityField = "qty",
                                        quoteQuantityField = "quoteQty",
                                        fee =
                                            FeeRule(
                                                amount = ValueExpr(listOf("commission")),
                                                currency = ValueExpr(listOf("commissionAsset")),
                                            ),
                                        timestampField = "time",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        idField = "id",
                                        // Binance's numeric trade "id" is scoped per symbol, not global —
                                        // two different pairs can report the same id, so the composite key
                                        // (symbol + id) is what's actually unique.
                                        compositeIdFields = listOf("symbol", "id"),
                                        orderIdField = "orderId",
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
                                            compositeIdFields = listOf("asset", "projectId", "type", "time", "rewards"),
                                        ),
                                    counterpartyAccountName = "Binance Earn Rewards",
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
    fun coinbase(now: Instant): ApiImportStrategy {
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
                // Every row is one wallet's own signed movement (negative = out).
                direction = Direction.AmountSign(),
                itemFilters = listOf(Condition("status", ConditionOp.EQUALS, value = "completed")),
                // Buys/sells/converts and Advanced Trade fills: one row per wallet touched, grouped into a
                // trade by the id of the buy/sell/convert (or the Advanced Trade order) they belong to
                // rather than booked as transfers. The rows carry the exact settled amounts, so no
                // quantity is ever derived from a price.
                excludeWhen = listOf(Condition("type", ConditionOp.IN, value = "advanced_trade_fill,buy,retail_simple_dust,sell,trade")),
                ledgerTrades =
                    ApiLedgerTrades(
                        key = ValueExpr(listOf("trade.id", "buy.id", "sell.id", "advanced_trade_fill.order_id")),
                        unpairedCounterAmountPath = "native_amount",
                        unpairedFundingAccountName = "Coinbase Payment Methods",
                    ),
                // An Advanced Trade fill's commission is settled outside its legs, and both legs repeat it:
                // book it once, on the leg in the pair's quote asset.
                fee =
                    FeeRule(
                        amount = ValueExpr(listOf("advanced_trade_fill.commission")),
                        // Charged once, on the leg in the pair's quote asset: what follows the symbol's last "-".
                        chargedOnAsset = ValueExpr(listOf("advanced_trade_fill.product_id"), Extraction("^.*-(.*)$", "$1")),
                    ),
            )

        return ApiImportStrategy(
            id = ApiImportStrategyId(coinbaseStrategyId),
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
                            "Paste the API secret (or the whole downloaded key file) below as the API secret.",
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }

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
    fun bybit(now: Instant): ApiImportStrategy {
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
        ) = ApiEndpointConfig(
            path = path,
            responseArrayKey = arrayKey,
            queryParams = queryParams,
            pagination = pagination,
            successCodeField = "retCode",
            successCodeOkValue = "0",
        )

        fun status(value: String) = listOf(Condition("status", ConditionOp.EQUALS, value = value))

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
                        direction = Direction.AmountSign(),
                        descriptionField = "type",
                        timestampField = "transactionTime",
                        timestampFormat = TimestampFormat.EPOCH_MS,
                        amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                        itemFilters =
                            listOf(
                                Condition("type", ConditionOp.IN, value = "TRADE,SETTLEMENT,DELIVERY,LIQUIDATION,ADL"),
                            ),
                    ),
                counterpartyAccountName = "Bybit Derivatives",
            )

        return ApiImportStrategy(
            id = ApiImportStrategyId(bybitStrategyId),
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
                                        sideField = "side",
                                        buyValues = setOf("Buy"),
                                        baseQuantityField = "execQty",
                                        quoteQuantityField = "execValue",
                                        fee = FeeRule(amount = ValueExpr(listOf("execFee")), currency = ValueExpr(listOf("feeCurrency"))),
                                        timestampField = "execTime",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        idField = "execId",
                                        orderIdField = "orderId",
                                        itemFilters = listOf(Condition("execType", ConditionOp.EQUALS, value = "Trade")),
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
                                        itemFilters = status("3"),
                                    ),
                                counterpartyAccountName = "Bybit Funding",
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
                                        fee = FeeRule(amount = ValueExpr(listOf("withdrawFee"))),
                                        itemFilters = status("success"),
                                    ),
                                counterpartyAccountName = "Bybit Funding",
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
                                        itemFilters = status("2"),
                                    ),
                                counterpartyAccountName = "Bybit Funding",
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
                                        fixedSideBuy = true,
                                        baseQuantityField = "toAmount",
                                        quoteQuantityField = "fromAmount",
                                        timestampField = "createdAt",
                                        timestampFormat = TimestampFormat.EPOCH_MS,
                                        idField = "exchangeTxId",
                                        itemFilters =
                                            listOf(
                                                Condition("exchangeStatus", ConditionOp.EQUALS, value = "success"),
                                            ),
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
                                        direction = Direction.Field(path = "orderType", incomingValues = setOf("Redeem")),
                                        itemFilters = status("Success"),
                                    ),
                                counterpartyAccountName = earnAccount,
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
                                        itemFilters = status("Success"),
                                    ),
                                counterpartyAccountName = "Bybit Earn Rewards",
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
