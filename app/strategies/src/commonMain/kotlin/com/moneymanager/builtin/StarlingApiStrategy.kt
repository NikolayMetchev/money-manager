package com.moneymanager.builtin

import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiDateWindowing
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiPaging
import com.moneymanager.domain.model.apistrategy.ApiPeopleMappings
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.WindowBoundFormat
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.RuleAction
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal object StarlingApiStrategy {
    private val STRATEGY_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000005")

    private const val STARLING_FEED_PATH = "/api/v2/feed/account/{account.id}/category/{account.defaultCategory}"

    /**
     * The built-in Starling Bank API import strategy. Starling is a flat two-level API like
     * Monzo: a single Bearer token lists accounts, and each account's transaction feed is fetched
     * from a path templated with the account id and its `defaultCategory`. Amounts are integer minor
     * units and the direction lives in a separate `direction` field (IN/OUT). The paginated endpoint
     * can span the full history in a single date range. The account holder is a single global object
     * (`/api/v2/account-holder/individual`) linked to every imported account.
     */
    fun strategy(now: Instant): ApiImportStrategy =
        ApiImportStrategy(
            id = ApiImportStrategyId(STRATEGY_ID),
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
                                    rules = listOf(accounts(AccountSide.OWN, fixedNaming("accountUid", "Starling"))),
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
                                        path = "$STARLING_FEED_PATH/paginated-transactions",
                                        responseArrayKey = "feedItems",
                                        historicalPaths =
                                            listOf(
                                                STARLING_FEED_PATH,
                                                "$STARLING_FEED_PATH/transactions-between",
                                            ),
                                        pagination =
                                            ApiPaginationConfig(
                                                window =
                                                    ApiDateWindowing(
                                                        startParam = "minTransactionTimestamp",
                                                        endParam = "maxTransactionTimestamp",
                                                        windowDays = null,
                                                        lookbackDays = 365 * 20,
                                                        boundFormat = WindowBoundFormat.ISO_8601,
                                                    ),
                                                paging =
                                                    ApiPaging.Token(
                                                        tokenField = "links.next",
                                                        param = "cursor",
                                                        linkQueryParams = listOf("pageToFetch"),
                                                    ),
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
                                        idField = "feedItemUid",
                                        // counterPartyUid is the fallback counterparty-account id; bank details
                                        // (sub-entity sort code + account number) take precedence where present, see
                                        // peopleMappings.preferBankIdentity. A single real account can otherwise be
                                        // split across uids (e.g. the same account as both a payee and a sender).
                                        counterpartyIdField = "counterPartyUid",
                                        rules =
                                            directionFromField("direction", incomingValues = "IN", amountField = "amount.minorUnits") +
                                                listOf(
                                                    accounts(AccountSide.COUNTERPARTY, naming("counterPartyName")),
                                                    // Declined feed items never moved money; import them but exclude from
                                                    // balances (same treatment as Monzo's `decline_reason`), keyed off
                                                    // Starling's status.
                                                    declinedWhen(Condition("status", ConditionOp.IN, value = "DECLINED")),
                                                    // PAYEE/SENDER counterparties are people; MERCHANT/STARLING are not.
                                                    rule(
                                                        RuleAction.CounterpartyIsPerson,
                                                        conditions =
                                                            listOf(
                                                                Condition(
                                                                    "counterPartyType",
                                                                    ConditionOp.MATCHES,
                                                                    value = "^(Personal|PAYEE|SENDER)$",
                                                                ),
                                                            ),
                                                    ),
                                                    // Persist the feed item's stable id as a transaction attribute so each
                                                    // imported transfer is uniquely identifiable and re-imports dedupe on it.
                                                    attribute("feedItemUid", "starling-transaction-id", unique = true),
                                                ),
                                    ),
                            ),
                        ),
                    // Per-account endpoint that returns the account's own sort code + account number, so the
                    // source account can be matched/merged with counterparties other providers create for it.
                    // Starling's counterparty fields are flat on the feed item (no nested object), so the
                    // counterparty object path is blank (the item itself). counterPartyUid identifies the person.
                    peopleMappings =
                        ApiPeopleMappings(
                            counterpartyObjectField = "",
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
}
