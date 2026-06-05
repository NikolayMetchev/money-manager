@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.wise

import com.moneymanager.database.port.DbEntitySource
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.rest.ApiSessionTrafficRecorder
import com.moneymanager.rest.createApiClient
import com.moneymanager.test.database.DbTest
import com.moneymanager.ui.api.downloadApiSessionAccounts
import com.moneymanager.ui.api.downloadApiSessionTransactions
import com.moneymanager.ui.api.importApiSessionTransactions
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * End-to-end import against the built-in Wise strategy, exercising the parts of the generic engine
 * that Monzo does not: a three-level hierarchy (profiles → balances → statement) with ids in the
 * URL path, bare top-level JSON arrays, decimal amounts in nested objects, sign taken from a
 * separate `type` field, and date-window pagination (which fetches the same statement across
 * several windows — the importer must de-duplicate by referenceNumber).
 */
private const val PROFILE_ID = "111"
private const val BALANCE_ID = "222"

private val PROFILES_JSON =
    """
[
  { "id": $PROFILE_ID, "type": "personal" }
]
    """.trimIndent()

private val BALANCES_JSON =
    """
[
  { "id": $BALANCE_ID, "currency": "GBP", "name": "GBP", "type": "STANDARD" }
]
    """.trimIndent()

/**
 *   tx1 – CREDIT (incoming) 500.00 GBP from "Alice Example"
 *   tx2 – DEBIT  (outgoing) 12.34 GBP to merchant "Coffee Shop Ltd" (amount value already signed)
 */
private val STATEMENT_JSON =
    """
{
  "transactions": [
    {
      "type": "CREDIT",
      "date": "2024-06-02T14:30:00.000Z",
      "amount": { "value": 500.00, "currency": "GBP" },
      "totalFees": { "value": 0, "currency": "GBP" },
      "details": { "type": "DEPOSIT", "description": "Received from Alice", "senderName": "Alice Example" },
      "referenceNumber": "TRANSFER-1001"
    },
    {
      "type": "DEBIT",
      "date": "2024-06-03T09:15:00.000Z",
      "amount": { "value": -12.34, "currency": "GBP" },
      "totalFees": { "value": 0, "currency": "GBP" },
      "details": { "type": "CARD", "description": "Coffee at shop", "merchant": { "name": "Coffee Shop Ltd" } },
      "referenceNumber": "CARD-2002"
    }
  ]
}
    """.trimIndent()

class WiseImportE2ETest : DbTest() {
    @Test
    fun `wise profiles balances and statements import into accounts and transactions`() =
        runTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val sessionId =
                repositories.apiSessionRepository.createSession(
                    token = "test-wise-token",
                    deviceId = deviceId,
                    createdAt = now,
                    expiresAt = null,
                )

            // Order matters: statement and balances paths both start with /v1/profiles.
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
                    respond(
                        content = json,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val apiClient =
                createApiClient(
                    trafficRecorder =
                        ApiSessionTrafficRecorder(
                            sessionId = sessionId,
                            apiSessionRepository = repositories.apiSessionRepository,
                        ),
                    engine = mockEngine,
                )
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Wise" }

            downloadApiSessionAccounts(
                token = "test-wise-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
            )
            downloadApiSessionTransactions(
                token = "test-wise-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
            )

            val importResult =
                importApiSessionTransactions(
                    apiSessionRepository = repositories.apiSessionRepository,
                    accountRepository = repositories.accountRepository,
                    currencyRepository = repositories.currencyRepository,
                    transactionRepository = repositories.transactionRepository,
                    entitySource = DbEntitySource(repositories.entitySourceQueries, repositories.transferSourceQueries, deviceId),
                    personRepository = repositories.personRepository,
                    personAccountOwnershipRepository = repositories.personAccountOwnershipRepository,
                    personAttributeRepository = repositories.personAttributeRepository,
                    attributeTypeRepository = repositories.attributeTypeRepository,
                    accountAttributeRepository = repositories.accountAttributeRepository,
                    deviceId = deviceId,
                    sessionId = sessionId,
                    strategy = strategy,
                )

            // One balance => one own account; the two unique statement rows import once each even
            // though date-window pagination replays them across several windows.
            assertEquals(1, importResult.accountCount, "One Wise balance maps to one own account")
            assertEquals(2, importResult.transactionCount, "Two unique statement rows should import once each")
            assertEquals(0, importResult.errorCount, "Should have no import errors")

            val accounts = repositories.accountRepository.getAllAccounts().first()
            val ownAccount = accounts.single { it.name == "Wise: GBP" }
            assertNotNull(accounts.singleOrNull { it.name == "Wise Counterparty: Alice Example" }, "credit counterparty account")
            assertNotNull(accounts.singleOrNull { it.name == "Wise Counterparty: Coffee Shop Ltd" }, "debit merchant account")

            val transfers = repositories.transactionRepository.getTransactionsByAccount(ownAccount.id).first()
            assertEquals(2, transfers.size, "Own account should have exactly two transfers")

            // CREDIT: 500.00 GBP incoming (target is our account); decimal -> 50000 minor units.
            val credit = transfers.single { it.amount.amount == 50_000L }
            assertEquals(ownAccount.id, credit.targetAccountId, "Credit should be incoming to the Wise account")

            // DEBIT: 12.34 GBP outgoing (source is our account); decimal -> 1234 minor units.
            val debit = transfers.single { it.amount.amount == 1_234L }
            assertEquals(ownAccount.id, debit.sourceAccountId, "Debit should be outgoing from the Wise account")

            assertTrue(credit.amount.amount > 0 && debit.amount.amount > 0, "Stored magnitudes are positive")
        }

    @Test
    fun `importing an accounts-only session creates accounts without any transactions`() =
        runTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val sessionId =
                repositories.apiSessionRepository.createSession(
                    token = "test-wise-token",
                    deviceId = deviceId,
                    createdAt = now,
                    expiresAt = null,
                )

            // Only profiles + balances are served; statements are never requested for this session.
            val mockEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val json =
                        when {
                            url.contains("/balances") -> BALANCES_JSON
                            url.contains("/v1/profiles") -> PROFILES_JSON
                            else -> error("Unexpected request: $url")
                        }
                    respond(content = json, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val apiClient =
                createApiClient(
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId = sessionId, apiSessionRepository = repositories.apiSessionRepository),
                    engine = mockEngine,
                )
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Wise" }

            downloadApiSessionAccounts(
                token = "test-wise-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
            )

            val importResult =
                importApiSessionTransactions(
                    apiSessionRepository = repositories.apiSessionRepository,
                    accountRepository = repositories.accountRepository,
                    currencyRepository = repositories.currencyRepository,
                    transactionRepository = repositories.transactionRepository,
                    entitySource = DbEntitySource(repositories.entitySourceQueries, repositories.transferSourceQueries, deviceId),
                    personRepository = repositories.personRepository,
                    personAccountOwnershipRepository = repositories.personAccountOwnershipRepository,
                    personAttributeRepository = repositories.personAttributeRepository,
                    attributeTypeRepository = repositories.attributeTypeRepository,
                    accountAttributeRepository = repositories.accountAttributeRepository,
                    deviceId = deviceId,
                    sessionId = sessionId,
                    strategy = strategy,
                )

            assertEquals(0, importResult.transactionCount, "No transactions are downloaded for an accounts-only session")
            val accounts = repositories.accountRepository.getAllAccounts().first()
            assertNotNull(
                accounts.singleOrNull { it.name == "Wise: GBP" },
                "The Wise balance must be created as an account even with no owners and no transactions",
            )
        }
}
