@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.monzo

import com.moneymanager.apiimporter.importApiSessionTransactions
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

private val fixtureDir = File("app/db/core/src/commonTest/resources/monzo/sample-apis")
private val balancesFile = fixtureDir.resolve("balances.json")

private val json =
    Json {
        ignoreUnknownKeys = true
    }

@Serializable
private data class SessionFixture(
    val id: Long,
    val token: String,
    val deviceId: Long,
    val createdAt: Long,
    val expiresAt: Long? = null,
    val credentialId: Long? = null,
    val importedAt: Long? = null,
)

@Serializable
private data class RequestFixture(
    val id: Long,
    val sessionId: Long,
    val requestedAt: Long,
    val method: String,
    val url: String,
)

@Serializable
private data class ResponseFixture(
    val id: Long,
    val requestId: Long,
    val sessionId: Long,
    val respondedAt: Long,
    val json: String,
)

@Serializable
private data class ExpectedBalance(
    val accountId: Long,
    val accountName: String,
    val currencyId: Long,
    val balance: Long,
)

class MonzoBalanceFixtureE2ETest : DbTest() {
    @Test
    fun `imported monzo balances match fixtures`() =
        runTest {
            if (!fixtureDir.exists() || !balancesFile.exists()) return@runTest

            val sessions = loadSessions()
            val requests = loadRequests()
            val responses = loadResponses()

            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("fixture-machine", "fixture-os"))
            val requestIdMap = mutableMapOf<Long, ApiRequestId>()

            // Accounts, transactions and people are now downloaded into one session, so collapse the
            // fixture's separate accounts/transactions sessions (same token) into a single session.
            val firstSession = sessions.minByOrNull { it.id } ?: return@runTest
            val sessionId =
                repositories.apiSessionRepository.createSession(
                    token = firstSession.token,
                    deviceId = deviceId,
                    createdAt = Instant.fromEpochMilliseconds(firstSession.createdAt),
                    expiresAt = firstSession.expiresAt?.let(Instant.Companion::fromEpochMilliseconds),
                )

            requests.sortedBy { it.id }.forEach { fixture ->
                val created =
                    repositories.apiSessionRepository.insertRequest(
                        sessionId = sessionId,
                        method = fixture.method,
                        url = fixture.url,
                        headers = emptyMap(),
                    )
                requestIdMap[fixture.id] = created
            }

            responses.sortedBy { it.id }.forEach { fixture ->
                repositories.apiSessionRepository.insertResponse(
                    requestId = requestIdMap.getValue(fixture.requestId),
                    sessionId = sessionId,
                    json = fixture.json,
                )
            }

            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Monzo" }

            importApiSessionTransactions(
                apiSessionRepository = repositories.apiSessionRepository,
                currencyRepository = repositories.currencyRepository,
                attributeTypeRepository = repositories.attributeTypeRepository,
                sessionId = sessionId,
                strategy = strategy,
                importEngine = repositories.importEngine,
            )

            repositories.transactionRepository.getAccountBalances().first().let { actualBalances ->
                val expectedBalances = json.decodeFromString<List<ExpectedBalance>>(balancesFile.readText())
                val expectedByName = expectedBalances.associateBy { it.accountName }
                val actualByName =
                    actualBalances.associateBy { balance ->
                        repositories.accountRepository
                            .getAccountById(balance.accountId)
                            .first()!!
                            .name
                    }
                assertEquals(expectedByName.keys.sorted(), actualByName.keys.sorted())
                expectedByName.forEach { (accountName, expected) ->
                    assertEquals(expected.balance, actualByName.getValue(accountName).balance.amount, accountName)
                }
            }
        }

    private fun loadSessions(): List<SessionFixture> = json.decodeFromString(File(fixtureDir, "api_session.json").readText())

    private fun loadRequests(): List<RequestFixture> = json.decodeFromString(File(fixtureDir, "api_request.json").readText())

    private fun loadResponses(): List<ResponseFixture> = json.decodeFromString(File(fixtureDir, "api_response.json").readText())
}
