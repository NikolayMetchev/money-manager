package com.moneymanager.ui.starling

import com.moneymanager.apiimporter.downloadApiSessionAccounts
import com.moneymanager.apiimporter.downloadApiSessionTransactions
import com.moneymanager.apiimporter.importApiSessionTransactions
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.rest.ApiSessionTrafficRecorder
import com.moneymanager.rest.createApiClient
import com.moneymanager.test.database.DbTest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

private const val ACCOUNT_UID = "11111111-1111-1111-1111-111111111111"
private const val CATEGORY_UID = "22222222-2222-2222-2222-222222222222"

private val ACCOUNTS_JSON =
    """
{"accounts":[{"accountUid":"$ACCOUNT_UID","accountType":"PRIMARY","defaultCategory":"$CATEGORY_UID",
"currency":"GBP","createdAt":"2021-01-01T00:00:00.000Z","name":"Personal"}]}
    """.trimIndent()

private val FEED_JSON =
    """
{"feedItems":[
{"feedItemUid":"f1","categoryUid":"$CATEGORY_UID","amount":{"currency":"GBP","minorUnits":1250},"direction":"OUT",
"transactionTime":"2024-06-03T09:15:00.000Z","reference":"COFFEE SHOP","counterPartyName":"Coffee Shop",
"runningBalance":98765,"balanceCcy":"QQQ"}
]}
    """.trimIndent()

class StarlingCustomRulesE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private fun mockEngine() =
        MockEngine { request ->
            val url = request.url.toString()
            val json =
                when {
                    url.contains("/feed/account/") -> FEED_JSON
                    url.contains("/api/v2/accounts") -> ACCOUNTS_JSON
                    else -> error("Unexpected request: $url")
                }
            respond(content = json, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }

    private suspend fun starlingWith(
        feedRules: List<StrategyRule> = emptyList(),
        accountRules: List<StrategyRule> = emptyList(),
    ): ApiImportStrategy {
        val builtIn =
            repositories.apiImportStrategyRepository
                .getAllStrategies()
                .first()
                .single { it.name == "Starling" }
        val endpoints =
            builtIn.config.dataEndpoints.map { data ->
                data.copy(
                    transactionMappings = data.transactionMappings?.let { it.copy(rules = it.rules + feedRules) },
                )
            }
        val accounts = builtIn.config.accounts as ApiAccountsSource.Downloaded
        val accountMappings = accounts.mappings.copy(rules = accounts.mappings.rules + accountRules)
        return builtIn.copy(
            config = builtIn.config.copy(dataEndpoints = endpoints, accounts = accounts.copy(mappings = accountMappings)),
        )
    }

    private suspend fun newSession(): ApiSessionId {
        val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
        return repositories.apiSessionRepository.createSession(deviceId, Instant.fromEpochMilliseconds(1_700_000_000_000L), null)
    }

    private suspend fun downloadAndImport(
        strategy: ApiImportStrategy,
        sessionId: ApiSessionId,
    ): Pair<Int, Int> {
        val apiClient =
            createApiClient(
                trafficRecorder = ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
                engine = mockEngine(),
            )
        downloadApiSessionAccounts(
            token = "test-starling-token",
            apiClient = apiClient,
            apiSessionRepository = repositories.apiSessionRepository,
            sessionId = sessionId,
            strategy = strategy,
        )
        downloadApiSessionTransactions(
            token = "test-starling-token",
            apiClient = apiClient,
            apiSessionRepository = repositories.apiSessionRepository,
            sessionId = sessionId,
            strategy = strategy,
            importEngine = repositories.importEngine,
        )
        val result =
            importApiSessionTransactions(
                apiSessionRepository = repositories.apiSessionRepository,
                currencyRepository = repositories.currencyRepository,
                sessionId = sessionId,
                strategy = strategy,
                importEngine = repositories.importEngine,
            )
        return result.transactionCount to result.errorCount
    }

    private fun balanceRule(currency: ValueExpr?) =
        StrategyRule(actions = listOf(RuleAction.Balance(amount = ValueExpr.of("runningBalance"), currency = currency)))

    @Test
    fun `a reported balance defaults to the item currency`() =
        runDbTest {
            val strategy = starlingWith(feedRules = listOf(balanceRule(currency = null)))

            assertEquals(1 to 0, downloadAndImport(strategy, newSession()))
        }

    @Test
    fun `a reported balance in an unknown currency fails its item only`() =
        runDbTest {
            val strategy = starlingWith(feedRules = listOf(balanceRule(currency = ValueExpr.of("balanceCcy"))))

            assertEquals(0 to 1, downloadAndImport(strategy, newSession()))
        }

    @Test
    fun `an item that is all fee is booked to the fee account`() =
        runDbTest {
            val feeRule =
                StrategyRule(
                    actions =
                        listOf(
                            RuleAction.Fee(
                                FeeRule(amount = ValueExpr.of("amount.minorUnits"), includedInAmount = true),
                            ),
                        ),
                )
            val strategy = starlingWith(feedRules = listOf(feeRule))

            assertEquals(1 to 0, downloadAndImport(strategy, newSession()))
            assertEquals(
                true,
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .any { it.name.contains("Fees") },
            )
        }

    @Test
    fun `unique identifier attributes dedupe a second import of the same feed`() =
        runDbTest {
            val unique =
                StrategyRule(
                    actions =
                        listOf(
                            RuleAction.Attribute(
                                typeName = "starling-ref",
                                value = RuleValue.read("feedItemUid"),
                                uniqueIdentifier = true,
                            ),
                        ),
                )
            val strategy = starlingWith(feedRules = listOf(unique))

            assertEquals(1 to 0, downloadAndImport(strategy, newSession()))
            val (imported, errors) = downloadAndImport(strategy, newSession())

            assertEquals(0, errors)
            assertEquals(0, imported, "the second import should find the transfer already there")
        }

    @Test
    fun `an account rule attribute lands on the own account`() =
        runDbTest {
            val tag =
                StrategyRule(
                    actions = listOf(RuleAction.Attribute(typeName = "starling-account-type", value = RuleValue.read("accountType"))),
                )
            val strategy = starlingWith(accountRules = listOf(tag))

            assertEquals(1 to 0, downloadAndImport(strategy, newSession()))
        }
}
