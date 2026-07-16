@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens.apistrategy.editor

import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiAccountBridge
import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
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
import com.moneymanager.domain.model.apistrategy.NonceFormat
import com.moneymanager.domain.model.apistrategy.NonceSpec
import com.moneymanager.domain.model.apistrategy.PaginationMode
import com.moneymanager.domain.model.apistrategy.PredicateOp
import com.moneymanager.domain.model.apistrategy.RulePredicate
import com.moneymanager.domain.model.apistrategy.RuleSign
import com.moneymanager.domain.model.apistrategy.SecretEncoding
import com.moneymanager.domain.model.apistrategy.SigFieldLocation
import com.moneymanager.domain.model.apistrategy.SigPart
import com.moneymanager.domain.model.apistrategy.SignatureEncoding
import com.moneymanager.domain.model.apistrategy.SigningAlgorithm
import com.moneymanager.domain.model.apistrategy.TransferDirection
import com.moneymanager.domain.model.apistrategy.WindowBoundFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ApiStrategyEditorStateTest {
    private val now = Instant.fromEpochMilliseconds(1_000)

    /** A strategy exercising every nested config type, including all the previously-unexposed fields. */
    private fun fullStrategy(): ApiImportStrategy =
        ApiImportStrategy(
            id = ApiImportStrategyId(Uuid.random()),
            name = "Full",
            baseUrl = "https://api.example.com",
            authType = ApiAuthType.BEARER_TOKEN,
            accountsEndpoint = ApiEndpointConfig(path = "/accounts", responseArrayKey = "accounts"),
            transactionsEndpoint =
                ApiEndpointConfig(
                    path = "/transactions",
                    responseArrayKey = "transactions",
                    queryParams = listOf(ApiQueryParam(name = "account_id", dynamicSource = "account.id")),
                    pagination =
                        ApiPaginationConfig(
                            mode = PaginationMode.DATE_WINDOW,
                            windowDays = 90,
                            lookbackDays = 720,
                            extraParams = listOf(ApiQueryParam(name = "currency", value = "GBP")),
                        ),
                ),
            accountMappings =
                ApiAccountMappings(
                    sortCodeField = "sortCode",
                    accountNumberField = "accountNumber",
                    currencyField = "currency",
                    customFields = mapOf("kind" to "type"),
                    uniqueIdentifierFields = setOf("kind"),
                ),
            transactionMappings =
                ApiTransactionMappings(
                    amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                    signSource = ApiSignSource.FIELD,
                    signField = "direction",
                    creditValues = setOf("CREDIT"),
                    declineStatusField = "status",
                    declinedStatusValues = setOf("DECLINED"),
                    feeAmountField = "fee",
                    customFields = mapOf("note" to "reference"),
                    uniqueIdentifierFields = setOf("note"),
                ),
            peopleMappings =
                ApiPeopleMappings(
                    personalBeneficiaryAccountTypeValues = setOf("PAYEE", "SENDER"),
                    preferBankIdentity = true,
                ),
            accountIdentifiersEndpoint =
                ApiEndpointConfig(path = "/accounts/{account.id}/identifiers", responseArrayKey = ""),
            ancestorEndpoints = listOf(ApiEndpointConfig(path = "/profiles", responseArrayKey = "")),
            builtInCounterpartyRules =
                listOf(
                    BuiltInCounterpartyRule(
                        name = "ATM",
                        onlyWhenSign = RuleSign.NEGATIVE,
                        predicates =
                            listOf(
                                RulePredicate(path = "metadata.mcc", op = PredicateOp.EQUALS, value = "6011"),
                                RulePredicate(path = "scheme", op = PredicateOp.EXISTS),
                            ),
                    ),
                ),
            signing = ApiSigningConfig(triggerStatus = 401, statementCountries = setOf("GB", "US")),
            peopleDownload =
                ApiPersonImportConfig(
                    endpoint = ApiEndpointConfig(path = "/profiles", responseArrayKey = ""),
                    firstNameField = "details.firstName",
                    lastNameField = "details.lastName",
                    ownsAllAccounts = true,
                ),
            personExternalIdAttribute = "example-external-id",
            tokenPageUrl = "https://example.com/developer/tokens",
            connectInstructions = listOf("Sign in.", "Create a token.", "Paste it below."),
            // A Kraken-style recipe: exercises the recursive Sha256 SigPart nesting.
            requestSigning =
                ApiRequestSigningConfig(
                    algorithm = SigningAlgorithm.HMAC_SHA512,
                    secretEncoding = SecretEncoding.BASE64,
                    signatureEncoding = SignatureEncoding.BASE64,
                    message = listOf(SigPart.Path, SigPart.Sha256(listOf(SigPart.Nonce, SigPart.Body))),
                    apiKey = FieldPlacement(SigFieldLocation.HEADER, "API-Key"),
                    nonce = NonceSpec(format = NonceFormat.EPOCH_MS, placement = FieldPlacement(SigFieldLocation.BODY_FIELD, "nonce")),
                    signature = FieldPlacement(SigFieldLocation.HEADER, "API-Sign"),
                    bodyFormat = BodyFormat.FORM_URLENCODED,
                ),
            dataEndpoints =
                listOf(
                    ApiDataEndpoint(
                        // Kraken-shaped: keyed-object response, error-array success check, offset paging.
                        endpoint =
                            ApiEndpointConfig(
                                path = "/private/get-trades",
                                responseArrayKey = "result.trades",
                                method = HttpMethodType.POST,
                                successCodeField = "code",
                                successCodeOkValue = "0",
                                errorArrayField = "error",
                                responseObjectValues = true,
                                itemKeyField = "trade_id",
                                pagination =
                                    ApiPaginationConfig(
                                        mode = PaginationMode.DATE_WINDOW,
                                        windowBoundFormat = WindowBoundFormat.EPOCH_S,
                                        offsetParam = "ofs",
                                        totalCountField = "result.count",
                                    ),
                            ),
                        kind = ApiEndpointKind.TRADES,
                        tradeMappings =
                            ApiTradeMappings(
                                instrumentField = "instrument_name",
                                sideField = "side",
                                baseQuantityField = "quantity",
                                priceField = "price",
                                timestampField = "create_time",
                                idField = "trade_id",
                                orderIdField = "order_id",
                            ),
                    ),
                    ApiDataEndpoint(
                        endpoint = ApiEndpointConfig(path = "/private/get-deposits", responseArrayKey = "result"),
                        kind = ApiEndpointKind.DEPOSITS,
                        transactionMappings =
                            ApiTransactionMappings(
                                amountField = "amount",
                                currencyField = "currency",
                                joinKeyField = "refid",
                                counterpartyAliasField = "address",
                                counterpartyAccountAliases = mapOf("INTERNAL_DEPOSIT" to "Crypto.com"),
                            ),
                        fixedDirection = TransferDirection.IN,
                        counterpartyAccountName = "Crypto.com Exchange Funding",
                    ),
                    ApiDataEndpoint(
                        endpoint = ApiEndpointConfig(path = "/private/deposit-status", responseArrayKey = "result"),
                        kind = ApiEndpointKind.DEPOSITS,
                        transactionMappings = ApiTransactionMappings(idField = "refid", txidField = "txid"),
                        enrichesTransfers = true,
                    ),
                ),
            syntheticAccount = ApiSyntheticAccount(name = "Crypto.com Exchange", externalId = "cryptocom-exchange"),
            internalTransferReconcile =
                ApiInternalTransferReconcile(
                    bridges = listOf(ApiAccountBridge(otherAccountName = "Crypto.com")),
                    windowSeconds = 3600,
                    amountTolerancePercent = "0.5",
                ),
            assetAliases = mapOf("XXBT" to "BTC", "ZUSD" to "USD"),
            createdAt = now,
            updatedAt = now,
        )

    @Test
    fun `round-trips every nested config field through extract then state then build`() {
        val strategy = fullStrategy()

        val extracted = extractFormStateFromStrategy(strategy)
        val state = ApiStrategyEditorState(extracted)
        assertEquals(extracted, state.toFormState())

        val rebuilt =
            buildStrategyFromApiFormState(
                state = state.toFormState(),
                id = strategy.id,
                createdAt = strategy.createdAt,
                updatedAt = strategy.updatedAt,
            )
        // revisionId/configJson are DB-owned and left at defaults by the editor; everything else matches.
        assertEquals(strategy.copy(revisionId = rebuilt.revisionId, configJson = rebuilt.configJson), rebuilt)
    }

    @Test
    fun `create-mode state is valid with defaults plus name and base url`() {
        val state = ApiStrategyEditorState(initial = null)
        assertFalse(state.isValid)
        state.name = "My API"
        state.baseUrl = "https://api.example.com"
        assertTrue(state.isValid)
    }

    @Test
    fun `sign field is required when sign source is FIELD`() {
        val state = ApiStrategyEditorState(initial = null)
        state.name = "My API"
        state.baseUrl = "https://api.example.com"
        state.transactionMappings = state.transactionMappings.copy(signSource = ApiSignSource.FIELD, signField = null)
        assertTrue(state.transactionMappingsHasError)
        state.transactionMappings = state.transactionMappings.copy(signField = "direction")
        assertFalse(state.transactionMappingsHasError)
    }

    @Test
    fun `people download forbids owns-all-accounts together with ancestor expression`() {
        val state = ApiStrategyEditorState(initial = null)
        state.name = "My API"
        state.baseUrl = "https://api.example.com"
        state.peopleDownload =
            ApiPersonImportConfig(
                endpoint = ApiEndpointConfig(path = "/profiles", responseArrayKey = ""),
                firstNameField = "firstName",
                ownsAllAccounts = true,
                accountOwnerAncestorExpr = "ancestor[0].id",
            )
        assertTrue(state.peopleHasError)
        state.peopleDownload = state.peopleDownload?.copy(accountOwnerAncestorExpr = null)
        assertFalse(state.peopleHasError)
    }
}
