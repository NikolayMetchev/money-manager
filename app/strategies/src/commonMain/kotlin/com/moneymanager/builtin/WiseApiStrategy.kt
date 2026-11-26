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
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.WindowBoundFormat
import com.moneymanager.domain.model.rules.AccountSide
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal object WiseApiStrategy {
    private val STRATEGY_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000002")

    /**
     * The built-in Wise API import strategy. Wise has a three-level hierarchy
     * (profiles → balances → statements): profiles are fetched as an ancestor endpoint and their id
     * is templated into the balances and statement paths; amounts are decimal with the direction in
     * a separate `type` field; statements are fetched in date windows.
     */
    fun strategy(now: Instant): ApiImportStrategy =
        ApiImportStrategy(
            id = ApiImportStrategyId(STRATEGY_ID),
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
                                    // Use the currency code instead so accounts are named "Wise: EUR" etc. (The
                                    // Wise CSV strategy books every currency to one "Wise" account instead.)
                                    descriptionField = "currency",
                                    ownersArrayField = null,
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
                                        idField = "referenceNumber",
                                        rules =
                                            directionFromField("type", incomingValues = "CREDIT", amountField = "amount.value") +
                                                accounts(AccountSide.COUNTERPARTY, naming("details.merchant.name", "details.senderName")),
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
}
