@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.monzo

import com.moneymanager.database.port.DbEntitySourcePort
import com.moneymanager.database.port.DbTransferSourcePort
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.ApiSessionKind
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.test.database.DbTest
import com.moneymanager.ui.api.importApiSessionTransactions
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
    val kind: String? = null,
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
            val sessionIdMap = mutableMapOf<Long, ApiSessionId>()
            val requestIdMap = mutableMapOf<Long, ApiRequestId>()

            sessions.sortedBy { it.id }.forEach { fixture ->
                val created =
                    repositories.apiSessionRepository.createSession(
                        token = fixture.token,
                        deviceId = deviceId,
                        createdAt = Instant.fromEpochMilliseconds(fixture.createdAt),
                        expiresAt = fixture.expiresAt?.let(Instant.Companion::fromEpochMilliseconds),
                        kind = fixture.kind?.let(ApiSessionKind::valueOf),
                    )
                sessionIdMap[fixture.id] = created
            }

            requests.sortedBy { it.id }.forEach { fixture ->
                val created =
                    repositories.apiSessionRepository.insertRequest(
                        sessionId = sessionIdMap.getValue(fixture.sessionId),
                        method = fixture.method,
                        url = fixture.url,
                        headers = emptyMap(),
                    )
                requestIdMap[fixture.id] = created
            }

            responses.sortedBy { it.id }.forEach { fixture ->
                repositories.apiSessionRepository.insertResponse(
                    requestId = requestIdMap.getValue(fixture.requestId),
                    sessionId = sessionIdMap.getValue(fixture.sessionId),
                    json = fixture.json,
                )
            }

            val firstAccountsSession =
                sessions.firstOrNull { it.kind == ApiSessionKind.ACCOUNTS.name }
                    ?: return@runTest
            val transactionSessions = sessions.filter { it.kind == ApiSessionKind.TRANSACTIONS.name }
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .first()

            importApiSessionTransactions(
                apiSessionRepository = repositories.apiSessionRepository,
                accountRepository = repositories.accountRepository,
                currencyRepository = repositories.currencyRepository,
                transactionRepository = repositories.transactionRepository,
                transferSourcePort = DbTransferSourcePort(transferSourceQueries, DeviceId(deviceId.id)),
                entitySourcePort = DbEntitySourcePort(database.entitySourceQueries, DeviceId(deviceId.id)),
                personRepository = repositories.personRepository,
                personAccountOwnershipRepository = repositories.personAccountOwnershipRepository,
                personAttributeRepository = repositories.personAttributeRepository,
                attributeTypeRepository = repositories.attributeTypeRepository,
                accountAttributeRepository = repositories.accountAttributeRepository,
                deviceId = DeviceId(deviceId.id),
                sessionId = sessionIdMap.getValue(firstAccountsSession.id),
                strategy = strategy,
            )

            transactionSessions.sortedBy { it.id }.forEach { fixture ->
                importApiSessionTransactions(
                    apiSessionRepository = repositories.apiSessionRepository,
                    accountRepository = repositories.accountRepository,
                    currencyRepository = repositories.currencyRepository,
                    transactionRepository = repositories.transactionRepository,
                    transferSourcePort = DbTransferSourcePort(transferSourceQueries, DeviceId(deviceId.id)),
                    entitySourcePort = DbEntitySourcePort(database.entitySourceQueries, DeviceId(deviceId.id)),
                    personRepository = repositories.personRepository,
                    personAccountOwnershipRepository = repositories.personAccountOwnershipRepository,
                    personAttributeRepository = repositories.personAttributeRepository,
                    attributeTypeRepository = repositories.attributeTypeRepository,
                    accountAttributeRepository = repositories.accountAttributeRepository,
                    deviceId = DeviceId(deviceId.id),
                    sessionId = sessionIdMap.getValue(fixture.id),
                    strategy = strategy,
                )
            }

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
