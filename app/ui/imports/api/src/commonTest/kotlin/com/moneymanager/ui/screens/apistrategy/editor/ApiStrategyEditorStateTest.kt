package com.moneymanager.ui.screens.apistrategy.editor

import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiAccountBridge
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiDateWindowing
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiInternalTransferReconcile
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiPaging
import com.moneymanager.domain.model.apistrategy.ApiPeopleMappings
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.domain.model.apistrategy.ApiRequestSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiSignSource
import com.moneymanager.domain.model.apistrategy.ApiSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.ApiTradeMappings
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.BodyFormat
import com.moneymanager.domain.model.apistrategy.BuiltInCounterpartyRule
import com.moneymanager.domain.model.apistrategy.FieldPlacement
import com.moneymanager.domain.model.apistrategy.HttpMethodType
import com.moneymanager.domain.model.apistrategy.NonceFormat
import com.moneymanager.domain.model.apistrategy.NonceSpec
import com.moneymanager.domain.model.apistrategy.RuleSign
import com.moneymanager.domain.model.apistrategy.SecretEncoding
import com.moneymanager.domain.model.apistrategy.SigFieldLocation
import com.moneymanager.domain.model.apistrategy.SigPart
import com.moneymanager.domain.model.apistrategy.SignatureEncoding
import com.moneymanager.domain.model.apistrategy.SigningAlgorithm
import com.moneymanager.domain.model.apistrategy.TransferDirection
import com.moneymanager.domain.model.apistrategy.WindowBoundFormat
import com.moneymanager.domain.model.rules.AssetCodeRules
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
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
            config =
                ApiStrategyConfig(
                    baseUrl = "https://api.example.com",
                    accounts = ApiAccountsSource.Single(name = "Crypto.com Exchange", externalId = "cryptocom-exchange"),
                    dataEndpoints =
                        listOf(
                            ApiDataEndpoint(
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
                                        requestCostWeight = 2,
                                        pagination =
                                            ApiPaginationConfig(
                                                window = ApiDateWindowing(boundFormat = WindowBoundFormat.EPOCH_S),
                                                paging = ApiPaging.Offset(param = "ofs", totalCountField = "result.count"),
                                                incrementalOverlapDays = 3,
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
                    peopleMappings =
                        ApiPeopleMappings(
                            personalBeneficiaryAccountTypeValues = setOf("PAYEE", "SENDER"),
                            preferBankIdentity = true,
                        ),
                    builtInCounterpartyRules =
                        listOf(
                            BuiltInCounterpartyRule(
                                name = "ATM",
                                onlyWhenSign = RuleSign.NEGATIVE,
                                predicates =
                                    listOf(
                                        Condition("metadata.mcc", ConditionOp.EQUALS, value = "6011"),
                                        Condition("scheme", ConditionOp.EXISTS),
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
                    rateLimitMillis = 3_100L,
                    rateLimitErrorSubstrings = listOf("Rate limit exceeded", "Throttled"),
                    rateLimitBackoffMillis = 5_000L,
                    maxRateLimitRetries = 6,
                    assetCodes =
                        AssetCodeRules(
                            aliases =
                                mapOf(
                                    "XXBT" to "BTC",
                                    "ZUSD" to "USD",
                                ),
                            stripSuffixes = setOf(".F", ".S", ".M"),
                        ),
                    minorUnitDivisorOverrides =
                        mapOf(
                            "GBP" to 1000L,
                        ),
                    requestSigning =
                        ApiRequestSigningConfig(
                            algorithm = SigningAlgorithm.HMAC_SHA512,
                            secretEncoding = SecretEncoding.BASE64,
                            signatureEncoding = SignatureEncoding.BASE64,
                            message = listOf(SigPart.Path, SigPart.Sha256(listOf(SigPart.Nonce, SigPart.Body))),
                            apiKey = FieldPlacement(SigFieldLocation.HEADER, "API-Key"),
                            nonce =
                                NonceSpec(
                                    format = NonceFormat.EPOCH_MS,
                                    placement = FieldPlacement(SigFieldLocation.BODY_FIELD, "nonce"),
                                ),
                            signature = FieldPlacement(SigFieldLocation.HEADER, "API-Sign"),
                            bodyFormat = BodyFormat.FORM_URLENCODED,
                        ),
                    internalTransferReconcile =
                        ApiInternalTransferReconcile(
                            bridges = listOf(ApiAccountBridge(otherAccountName = "Crypto.com")),
                            windowSeconds = 3600,
                            amountTolerancePercent = "0.5",
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )

    @Test
    fun `round-trips every nested config field through editor state then build`() {
        val strategy = fullStrategy()

        val state = ApiStrategyEditorState(strategy)

        val rebuilt =
            state.buildStrategy(
                id = strategy.id,
                createdAt = strategy.createdAt,
                updatedAt = strategy.updatedAt,
            )
        // revisionId/configJson are DB-owned and left at defaults by the editor; everything else matches.
        assertEquals(strategy.copy(revisionId = rebuilt.revisionId, configJson = rebuilt.configJson), rebuilt)
    }

    @Test
    fun `create-mode state is valid with defaults plus name and base url`() {
        val state = ApiStrategyEditorState(strategy = null)
        assertFalse(state.isValid)
        state.name = "My API"
        state.updateConfig { copy(baseUrl = "https://api.example.com") }
        assertTrue(state.isValid)
    }

    @Test
    fun `sign field is required when sign source is FIELD`() {
        val state = ApiStrategyEditorState(strategy = null)
        state.name = "My API"
        state.updateConfig { copy(baseUrl = "https://api.example.com") }
        state.updateConfig { mapBankTransactionMappings { copy(signSource = ApiSignSource.FIELD, signField = null) } }
        assertTrue(state.transactionMappingsHasError)
        state.updateConfig { mapBankTransactionMappings { copy(signField = "direction") } }
        assertFalse(state.transactionMappingsHasError)
    }

    @Test
    fun `an incomplete exclusion or filter blocks saving`() {
        val state = ApiStrategyEditorState(strategy = null)
        state.name = "My API"
        state.updateConfig { copy(baseUrl = "https://api.example.com") }
        // A path-less "is blank" exclusion would hold for every item and exclude them all.
        state.updateConfig { mapBankTransactionMappings { copy(excludeWhen = listOf(Condition("", ConditionOp.BLANK))) } }
        assertTrue(state.transactionMappingsHasError)
        state.updateConfig { mapBankTransactionMappings { copy(excludeWhen = emptyList()) } }
        assertFalse(state.transactionMappingsHasError)
        state.updateConfig {
            mapBankTransactionMappings {
                copy(
                    itemFilters = listOf(Condition("status", ConditionOp.EQUALS, value = "")),
                )
            }
        }
        assertTrue(state.transactionMappingsHasError)
    }

    @Test
    fun `people download forbids owns-all-accounts together with ancestor expression`() {
        val state = ApiStrategyEditorState(strategy = null)
        state.name = "My API"
        state.updateConfig { copy(baseUrl = "https://api.example.com") }
        state.updateConfig {
            copy(
                peopleDownload =
                    ApiPersonImportConfig(
                        endpoint = ApiEndpointConfig(path = "/profiles", responseArrayKey = ""),
                        firstNameField = "firstName",
                        ownsAllAccounts = true,
                        accountOwnerAncestorExpr = "ancestor[0].id",
                    ),
            )
        }
        assertTrue(state.peopleHasError)
        state.updateConfig { copy(peopleDownload = peopleDownload?.copy(accountOwnerAncestorExpr = null)) }
        assertFalse(state.peopleHasError)
    }
}
