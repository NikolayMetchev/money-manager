@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.seed.generator

import com.moneymanager.database.json.ApiStrategyConfigJson
import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiAuthType
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiPeopleMappings
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiSignSource
import com.moneymanager.domain.model.apistrategy.ApiSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.BuiltInCounterpartyRule
import com.moneymanager.domain.model.apistrategy.PaginationMode
import com.moneymanager.domain.model.apistrategy.PredicateOp
import com.moneymanager.domain.model.apistrategy.RulePredicate
import com.moneymanager.domain.model.apistrategy.RuleSign
import kotlin.uuid.Uuid

/** Built-in API/CSV/QIF strategy definitions, moved verbatim from DatabaseConfig for build-time .sq generation. */
object BuiltInStrategies {
    internal val monzoStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000001")

    /** Seeds the built-in Monzo API import strategy during new-database initialisation. */
    fun monzoApiConfig(): ApiStrategyConfigJson =
        ApiStrategyConfigJson(
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

    /** Fixed UUID for the built-in Wise strategy so it can be referenced reliably. */
    internal val wiseStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000002")

    /**
     * Seeds the built-in Wise API import strategy. Wise has a three-level hierarchy
     * (profiles → balances → statements): profiles are fetched as an ancestor endpoint and their id
     * is templated into the balances and statement paths; amounts are decimal with the direction in
     * a separate `type` field; statements are fetched in date windows.
     */
    fun wiseApiConfig(): ApiStrategyConfigJson =
        ApiStrategyConfigJson(
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
        )

    /** Fixed UUID for the built-in Starling strategy so it can be referenced reliably. */
    internal val starlingStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000005")

    /**
     * Seeds the built-in Starling Bank API import strategy. Starling is a flat two-level API like
     * Monzo: a single Bearer token lists accounts, and each account's transaction feed is fetched
     * from a path templated with the account id and its `defaultCategory`. Amounts are integer minor
     * units and the direction lives in a separate `direction` field (IN/OUT). The whole feed is
     * returned in one response, so no pagination is configured. The account holder is a single global
     * object (`/api/v2/account-holder/individual`) linked to every imported account.
     */
    fun starlingApiConfig(): ApiStrategyConfigJson =
        ApiStrategyConfigJson(
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
        )
}
