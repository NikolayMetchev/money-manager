package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.moneymanager.builtin.BuiltInApiStrategies
import com.moneymanager.database.json.ApiStrategyExportCodec
import com.moneymanager.database.json.ApiStrategyJsonCodec
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
import com.moneymanager.domain.model.apistrategy.ApiSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.ApiTradeMappings
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.BodyFormat
import com.moneymanager.domain.model.apistrategy.FieldPlacement
import com.moneymanager.domain.model.apistrategy.HttpMethodType
import com.moneymanager.domain.model.apistrategy.NonceFormat
import com.moneymanager.domain.model.apistrategy.NonceSpec
import com.moneymanager.domain.model.apistrategy.SecretEncoding
import com.moneymanager.domain.model.apistrategy.SigFieldLocation
import com.moneymanager.domain.model.apistrategy.SigPart
import com.moneymanager.domain.model.apistrategy.SignatureEncoding
import com.moneymanager.domain.model.apistrategy.SigningAlgorithm
import com.moneymanager.domain.model.apistrategy.WindowBoundFormat
import com.moneymanager.domain.model.apistrategy.export.ApiStrategyExportMapper
import com.moneymanager.domain.model.rules.AssetCodeRules
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ApiStrategyEditorStateTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `edit date ranges next-page parameters and saved response paths`() =
        runMoneyManagerComposeUiTest {
            val state = ApiStrategyEditorState(BuiltInApiStrategies.starling(Clock.System.now()))

            fun endpoint() = checkNotNull(state.config.bankTransactions).endpoint
            val previousPath = endpoint().historicalPaths.first()
            setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        EndpointsTab(state, enabled = true)
                    }
                }
            }

            val split = onNodeWithContentDescription("Split history into fixed-length windows")
            split.performScrollTo().performClick()
            onNodeWithText("Window days").performScrollTo().performTextReplacement("90")
            runOnIdle { assertEquals(90, endpoint().pagination?.window?.windowDays) }

            split.performScrollTo().performClick()
            runOnIdle {
                val window = assertNotNull(endpoint().pagination?.window)
                assertNull(window.windowDays)
                assertEquals("minTransactionTimestamp", window.startParam)
            }

            onNodeWithText("pageToFetch").performScrollTo().performTextReplacement("direction")
            runOnIdle {
                val paging = assertIs<ApiPaging.Token>(endpoint().pagination?.paging)
                assertEquals(listOf("direction"), paging.linkQueryParams)
            }

            onNodeWithText(previousPath).performScrollTo().performTextReplacement("/older/{account.id}")
            runOnIdle { assertEquals("/older/{account.id}", endpoint().historicalPaths.first()) }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `author a Coinbase wallet fan-out and reopen its canonical export`() =
        runMoneyManagerComposeUiTest {
            val original = BuiltInApiStrategies.coinbase(now)
            val data = original.config.dataEndpoints.single()
            val state = ApiStrategyEditorState(null)
            state.name = original.name
            state.config = original.config.copy(dataEndpoints = listOf(data.copy(endpoint = data.endpoint.copy(fanOut = null))))
            setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        EndpointsTab(state, enabled = true)
                    }
                }
            }
            onNodeWithContentDescription("Download once per value").performScrollTo().performClick()
            onNodeWithContentDescription("Preserve value case").performScrollTo().performClick()
            onNodeWithText("Download values").performScrollTo().performClick()
            onNodeWithText("Value endpoint").performClick()
            onNodeWithText("Source endpoint path").performScrollTo().performTextReplacement("v2/accounts")
            onNodeWithText("Add field").performScrollTo().performClick()
            onNodeWithText("Field 1").performScrollTo().performTextReplacement("id")
            runOnIdle { assertCanonicalRoundTrip(original, state) }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `author Bybit server time and static header then reopen the strategy`() =
        runMoneyManagerComposeUiTest {
            val original = BuiltInApiStrategies.bybit(now)
            val signing = checkNotNull(original.config.requestSigning)
            val state = ApiStrategyEditorState(original)
            state.updateConfig { copy(requestSigning = signing.copy(staticHeaders = emptyMap(), serverTimeSync = null)) }
            setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        RequestSigningEditor(state.config.requestSigning, { s -> state.updateConfig { copy(requestSigning = s) } }, true)
                    }
                }
            }
            onNodeWithContentDescription("Synchronize nonce with server time").performScrollTo().performClick()
            onNodeWithText("Server time endpoint path").performScrollTo().performTextReplacement("v5/market/time")
            onNodeWithText("Server time field (epoch milliseconds)").performScrollTo().performTextReplacement("time")
            onNodeWithText("Add entry").performScrollTo().performClick()
            val header = signing.staticHeaders.entries.single()
            onNodeWithText("Header name").performScrollTo().performTextReplacement(header.key)
            onNodeWithText("Value").performScrollTo().performTextReplacement(header.value)
            runOnIdle { assertCanonicalRoundTrip(original, state) }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `author Binance signed parameter while preserving nested fan-out and window settings`() =
        runMoneyManagerComposeUiTest {
            val original = BuiltInApiStrategies.binance(now)
            val signing = checkNotNull(original.config.requestSigning)
            val state = ApiStrategyEditorState(original)
            state.updateConfig { copy(requestSigning = signing.copy(signedParams = emptyList())) }
            setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        RequestSigningEditor(state.config.requestSigning, { s -> state.updateConfig { copy(requestSigning = s) } }, true)
                    }
                }
            }
            onNodeWithText("Add Parameter").performScrollTo().performClick()
            onNodeWithText("Name").performScrollTo().performTextReplacement("recvWindow")
            onNodeWithText("Signed value").performScrollTo().performTextReplacement("60000")
            runOnIdle { assertCanonicalRoundTrip(original, state) }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `create the Binance public symbol endpoint without storing responses`() =
        runMoneyManagerComposeUiTest {
            val original = BuiltInApiStrategies.binance(now)
            val expected = original.config.valueEndpoints.single { it.unsigned }
            val state = ApiStrategyEditorState(null)
            state.name = original.name
            state.config = original.config.copy(dataEndpoints = emptyList(), valueEndpoints = emptyList())
            setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        EndpointsTab(state, enabled = true)
                    }
                }
            }
            onNodeWithText("Add value endpoint").performScrollTo().performClick()
            onNodeWithText("Path").performScrollTo().performTextReplacement(expected.path)
            onNodeWithText("Response array key").performScrollTo().performTextReplacement(expected.responseArrayKey)
            onNodeWithContentDescription("Send unsigned request").performScrollTo().performClick()
            onNodeWithContentDescription("Store response").performScrollTo().performClick()
            runOnIdle {
                assertCanonicalRoundTrip(
                    original.copy(config = original.config.copy(dataEndpoints = emptyList(), valueEndpoints = listOf(expected))),
                    state,
                )
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `edit and save a wallet balance snapshot mapping`() =
        runMoneyManagerComposeUiTest {
            val original = BuiltInApiStrategies.binance(now)
            val endpoint = original.config.valueEndpoints.single { it.balanceSnapshot != null }
            val mapping = checkNotNull(endpoint.balanceSnapshot)
            val expected = original.copy(config = original.config.copy(dataEndpoints = emptyList(), valueEndpoints = listOf(endpoint)))
            val state = ApiStrategyEditorState(expected)
            state.updateConfig { copy(valueEndpoints = listOf(endpoint.copy(balanceSnapshot = null))) }
            setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        EndpointsTab(state, enabled = true)
                    }
                }
            }
            onNodeWithContentDescription("Report wallet balances").performScrollTo().performClick()
            runOnIdle { assertFalse(state.isValid) }
            onNodeWithText("Asset field").performScrollTo().performTextReplacement(mapping.assetField)
            onNodeWithText("Amount fields to sum (comma separated)")
                .performScrollTo()
                .performTextReplacement(mapping.amountFields.joinToString(","))
            onNodeWithContentDescription("Omitted assets have zero balance").performScrollTo().performClick()
            onNodeWithContentDescription("Store response").performScrollTo().performClick()
            runOnIdle { assertFalse(state.isValid) }
            onNodeWithContentDescription("Store response").performScrollTo().performClick()
            runOnIdle { assertCanonicalRoundTrip(expected, state) }
        }

    private fun assertCanonicalRoundTrip(
        original: ApiImportStrategy,
        state: ApiStrategyEditorState,
    ) {
        assertTrue(state.isValid)
        val saved = state.buildStrategy(original.id, now, now)
        val reopened = ApiStrategyEditorState(saved.copy(config = ApiStrategyJsonCodec.decode(ApiStrategyJsonCodec.encode(saved.config))))
        val expected = ApiStrategyExportCodec.encode(ApiStrategyExportMapper.toExport(original, "test"))
        assertEquals(
            expected,
            ApiStrategyExportCodec.encode(ApiStrategyExportMapper.toExport(reopened.buildStrategy(original.id, now, now), "test")),
        )
    }

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
                                        baseQuantityField = "quantity",
                                        priceField = "price",
                                        timestampField = "create_time",
                                        idField = "trade_id",
                                        orderIdField = "order_id",
                                        rules = listOf(StrategyRule(actions = listOf(RuleAction.TradeSide(buy = true)))),
                                    ),
                            ),
                            ApiDataEndpoint(
                                endpoint = ApiEndpointConfig(path = "/private/get-deposits", responseArrayKey = "result"),
                                kind = ApiEndpointKind.DEPOSITS,
                                transactionMappings =
                                    ApiTransactionMappings(
                                        joinKeyField = "refid",
                                        rules =
                                            listOf(
                                                StrategyRule(
                                                    conditions =
                                                        listOf(
                                                            Condition("metadata.mcc", ConditionOp.EQUALS, value = "6011"),
                                                            Condition("amount", ConditionOp.NEGATIVE),
                                                        ),
                                                    actions = listOf(RuleAction.Skip),
                                                ),
                                            ),
                                    ),
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
                            preferBankIdentity = true,
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
                    maxRateLimitRetries = 6,
                    assetCodes =
                        AssetCodeRules(
                            stripSuffixes = setOf(".F", ".S", ".M"),
                        ),
                    minorUnitDecimalPlaces =
                        mapOf(
                            "GBP" to 3,
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
                            window = SettlementWindow.Elapsed(3600),
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
    fun `an incomplete bank feed rule blocks saving`() {
        val state = ApiStrategyEditorState(strategy = null)
        state.name = "My API"
        state.updateConfig { copy(baseUrl = "https://api.example.com") }
        // A path-less "is blank" condition would hold for every item and skip them all.
        val skipAll = StrategyRule(conditions = listOf(Condition("", ConditionOp.BLANK)), actions = listOf(RuleAction.Skip))
        state.updateConfig { mapBankTransactionMappings { copy(rules = listOf(skipAll)) } }
        assertTrue(state.rulesHasError)
        assertFalse(state.isValid)
        state.updateConfig {
            mapBankTransactionMappings { copy(rules = listOf(skipAll.copy(conditions = listOf(Condition("type", ConditionOp.BLANK))))) }
        }
        assertFalse(state.rulesHasError)
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
