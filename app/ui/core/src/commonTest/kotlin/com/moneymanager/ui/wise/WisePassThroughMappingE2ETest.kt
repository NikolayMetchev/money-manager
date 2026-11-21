package com.moneymanager.ui.wise

import com.moneymanager.apiimporter.ApiImportReads
import com.moneymanager.apiimporter.downloadApiSessionAccounts
import com.moneymanager.apiimporter.downloadApiSessionTransactions
import com.moneymanager.apiimporter.importApiSession
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.PassThroughAccountId
import com.moneymanager.domain.model.passthrough.PassThroughRule
import com.moneymanager.importengineapi.createAccountMapping
import com.moneymanager.regex.compileRegex
import com.moneymanager.rest.ApiSessionTrafficRecorder
import com.moneymanager.rest.createApiClient
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

private const val PROFILE_ID = "111"
private const val BALANCE_ID = "222"

private const val PROFILES_JSON = """[{ "id": $PROFILE_ID, "type": "personal", "details": { "firstName": "Ada", "lastName": "Lovelace" } }]"""

private const val BALANCES_JSON = """[{ "id": $BALANCE_ID, "currency": "GBP", "name": null, "type": "STANDARD" }]"""

private val STATEMENT_JSON =
    """
{
  "transactions": [
    {
      "type": "DEBIT",
      "date": "2024-06-03T09:15:00.000Z",
      "amount": { "value": -12.34, "currency": "GBP" },
      "totalFees": { "value": 0, "currency": "GBP" },
      "details": { "type": "CARD", "description": "Conduit*Tea Shop 1234", "merchant": { "name": "Ignored" } },
      "referenceNumber": "CARD-2002"
    }
  ]
}
    """.trimIndent()

/**
 * A bank-feed charge routed through a conduit ("Conduit*Tea Shop 1234") is booked against the account a global account
 * mapping names, rather than a new account named after the stripped merchant.
 */
class WisePassThroughMappingE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private val conduit =
        PassThroughAccount(
            id = PassThroughAccountId(1),
            name = "Conduit",
            conduitAccountName = "Conduit",
            rules =
                listOf(
                    PassThroughRule(
                        detectionPattern = "(?i)^Conduit\\*",
                        merchantPattern = "(?i)^Conduit\\*\\s*(.+?)$",
                    ),
                ),
        )

    private suspend fun importCharge(): Pair<ApiImportReads, Long> {
        val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
        val sessionId = repositories.apiSessionRepository.createSession(deviceId, now, null)
        val mockEngine =
            MockEngine { request ->
                val url = request.url.toString()
                val json =
                    when {
                        url.contains("statement.json") -> STATEMENT_JSON
                        url.contains("/balances") -> BALANCES_JSON
                        url.contains("/v1/profiles") -> PROFILES_JSON
                        else -> error("Unexpected request: $url")
                    }
                respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        val apiClient =
            createApiClient(
                trafficRecorder = ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
                engine = mockEngine,
            )
        val strategy =
            repositories.apiImportStrategyRepository
                .getAllStrategies()
                .first()
                .single { it.name == "Wise" }
        downloadApiSessionAccounts("token", apiClient, repositories.apiSessionRepository, sessionId, strategy)
        downloadApiSessionTransactions(
            token = "token",
            apiClient = apiClient,
            apiSessionRepository = repositories.apiSessionRepository,
            sessionId = sessionId,
            strategy = strategy,
            importEngine = repositories.importEngine,
        )
        val reads =
            ApiImportReads(
                apiSessionRepository = repositories.apiSessionRepository,
                accountRepository = repositories.accountRepository,
                accountAttributeRepository = repositories.accountAttributeRepository,
                currencyRepository = repositories.currencyRepository,
                cryptoRepository = repositories.cryptoRepository,
                importRecordRepository = repositories.importRecordRepository,
                accountMappingRepository = repositories.accountMappingRepository,
            )
        val outcome =
            importApiSession(
                reads = reads,
                sessionId = sessionId,
                strategy = strategy,
                importEngine = repositories.importEngine,
                passThroughAccounts = listOf(conduit),
            )
        assertEquals(0, outcome.errorCount, "no import errors")
        return reads to sessionId.id
    }

    @Test
    fun `a global mapping routes the pass-through merchant onto its account`() =
        runDbTest {
            val teaShop = repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Tea Shop", openingDate = now))
            repositories.importEngine.createAccountMapping(compileRegex("^Tea Shop 1234$", RegexOption.IGNORE_CASE), teaShop)

            importCharge()

            val accounts = repositories.accountRepository.getAllAccounts().first()
            assertNull(accounts.singleOrNull { it.name == "Tea Shop 1234" }, "no account is made for the stripped merchant text")
            val conduitAccount = assertNotNull(accounts.singleOrNull { it.name == "Conduit" })
            val spend =
                repositories.transactionRepository
                    .getTransactionsByAccount(teaShop)
                    .first()
                    .single()
            assertEquals(conduitAccount.id, spend.sourceAccountId, "the spend leg runs conduit -> the mapped account")
            assertEquals(teaShop, spend.targetAccountId)
        }

    @Test
    fun `without a mapping the pass-through merchant is created from the stripped text`() =
        runDbTest {
            importCharge()

            val accounts = repositories.accountRepository.getAllAccounts().first()
            assertNotNull(accounts.singleOrNull { it.name == "Tea Shop 1234" })
        }
}
