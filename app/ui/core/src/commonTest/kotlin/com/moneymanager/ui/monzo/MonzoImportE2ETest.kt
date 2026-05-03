@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.monzo

import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.SourceType
import com.moneymanager.rest.ApiSessionTrafficRecorder
import com.moneymanager.rest.createApiClient
import com.moneymanager.test.database.DbTest
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
 * Anonymised Monzo account used across fixtures.
 * Fields not relevant to the import are omitted; the parser only reads:
 *   id, description (accounts) / created, amount, currency, description,
 *   merchant.name, counterparty.name (transactions).
 */
private const val ACCOUNT_ID = "acc_00009TEST000000000001"
private const val ACCOUNT_DESCRIPTION = "user_00009TEST000000user"

private val ACCOUNTS_JSON =
    """
{
  "accounts": [
    {
      "id": "$ACCOUNT_ID",
      "closed": false,
      "created": "2022-01-01T00:00:00.000Z",
      "description": "$ACCOUNT_DESCRIPTION",
      "type": "uk_retail",
      "currency": "GBP"
    }
  ]
}
    """.trimIndent()

/**
 * Three anonymised transactions:
 *   tx1 – outgoing payment to a merchant  (amount negative, merchant.name set)
 *   tx2 – incoming payment from a person  (amount positive, counterparty.name set)
 *   tx3 – outgoing direct debit           (amount negative, description only)
 */
private val TRANSACTIONS_PAGE_1_JSON =
    """
{
  "transactions": [
    {
      "id": "tx_00009TEST000000000001",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-06-03T09:15:00.000Z",
      "amount": -1250,
      "currency": "GBP",
      "description": "COFFEE SHOP LTD LONDON GBR",
      "merchant": { "name": "Coffee Shop Ltd" },
      "counterparty": {}
    },
    {
      "id": "tx_00009TEST000000000002",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-06-02T14:30:00.000Z",
      "amount": 50000,
      "currency": "GBP",
      "description": "Sent from Alice",
      "merchant": null,
      "counterparty": { "name": "Alice Example" }
    },
    {
      "id": "tx_00009TEST000000000003",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-06-01T08:00:00.000Z",
      "amount": -9999,
      "currency": "GBP",
      "description": "EXAMPLE DIRECT DEBIT",
      "merchant": null,
      "counterparty": {}
    },
    {
      "id": "tx_00009TEST000000000004",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-05-31T10:00:00.000Z",
      "amount": 0,
      "currency": "GBP",
      "description": "CRV*Card Verification",
      "merchant": null,
      "counterparty": {}
    }
  ]
}
    """.trimIndent()

private const val EMPTY_TRANSACTIONS_JSON = """{ "transactions": [] }"""

class MonzoImportE2ETest : DbTest() {
    @Test
    fun `downloaded transactions are imported with correct API audit source on accounts and transfers`() =
        runTest {
            // GIVEN — device and session
            val deviceId =
                repositories.deviceRepository.getOrCreateDevice(
                    DeviceInfo.Jvm("test-machine", "Test OS"),
                )
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val sessionId =
                repositories.apiSessionRepository.createSession(
                    token = "test-monzo-token",
                    deviceId = deviceId,
                    createdAt = now,
                    expiresAt = null,
                )

            // Mock engine: returns fixed JSON per URL shape
            val mockEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val json =
                        when {
                            url.contains("/accounts") -> ACCOUNTS_JSON
                            url.contains("/transactions") && !url.contains("before=") -> TRANSACTIONS_PAGE_1_JSON
                            url.contains("/transactions") && url.contains("before=") -> EMPTY_TRANSACTIONS_JSON
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

            // WHEN — download then import
            downloadMonzoTransactions(token = "test-monzo-token", apiClient = apiClient)

            val importResult =
                importMonzoSessionTransactions(
                    apiSessionRepository = repositories.apiSessionRepository,
                    accountRepository = repositories.accountRepository,
                    currencyRepository = repositories.currencyRepository,
                    transactionRepository = repositories.transactionRepository,
                    transferSourceQueries = transferSourceQueries,
                    entitySourceQueries = repositories.entitySourceQueries,
                    deviceId = deviceId,
                    sessionId = sessionId,
                )

            // THEN — import counts
            assertEquals(4, importResult.transactionCount, "Should have imported 4 transactions")
            assertEquals(0, importResult.errorCount, "Should have no import errors")

            // --- Account audit ---
            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            val monzoAccount = allAccounts.single { it.name == "Monzo: $ACCOUNT_DESCRIPTION" }

            val accountAuditEntries = repositories.auditRepository.getAuditHistoryForAccount(monzoAccount.id)
            assertEquals(1, accountAuditEntries.size, "Monzo account should have exactly one audit entry (INSERT)")

            val accountAudit = accountAuditEntries.single()
            assertEquals(AuditType.INSERT, accountAudit.auditType)
            val accountSource = accountAudit.source
            assertNotNull(accountSource, "Account audit entry must have a source")
            assertEquals(SourceType.API, accountSource.sourceType)
            assertEquals(sessionId, accountSource.apiSource?.sessionId)
            assertEquals(JsonPath("$.accounts[0]"), accountSource.apiSource?.jsonPath)

            // The accounts list is request #1; its request_id must be present
            val accountsRequestId = accountSource.apiSource?.requestId
            assertNotNull(accountsRequestId, "Account source must reference the API request")

            // --- Transfer audit ---
            val transfers =
                repositories.transactionRepository
                    .getTransactionsByAccount(monzoAccount.id)
                    .first()
            assertEquals(4, transfers.size, "Should have 4 transfers for the Monzo account")

            // Zero-amount transaction should use the Void counterparty account
            val voidAccount = allAccounts.find { it.name == "Monzo Counterparty: Void" }
            assertNotNull(voidAccount, "Void counterparty account should be created for zero-amount transactions")
            val zeroTransfer = transfers.single { it.amount.amount == 0L }
            assertEquals(
                voidAccount.id,
                zeroTransfer.targetAccountId,
                "Zero-amount transaction target should be the Void counterparty",
            )

            transfers.forEachIndexed { index, transfer ->
                val auditEntries = repositories.auditRepository.getAuditHistoryForTransfer(transfer.id)
                assertEquals(
                    1,
                    auditEntries.size,
                    "Transfer ${transfer.id} should have exactly one audit entry (INSERT)",
                )
                val txAudit = auditEntries.single()
                assertEquals(AuditType.INSERT, txAudit.auditType)
                val txSource = txAudit.source
                assertNotNull(txSource, "Transfer audit entry must have a source")
                assertEquals(SourceType.API, txSource.sourceType)
                val txApiSource = txSource.apiSource
                assertNotNull(txApiSource, "Transfer $index source must have API details")
                assertEquals(
                    sessionId,
                    txApiSource.sessionId,
                    "Transfer $index source session should match the download session",
                )
                val path = txApiSource.jsonPath.value
                assert(path.startsWith("$.transactions[")) {
                    "Transfer $index json_path '$path' should be a $.transactions[N] path"
                }
            }

            // Verify each transaction maps to a distinct json_path
            val jsonPaths =
                transfers
                    .mapNotNull { t ->
                        val entry = repositories.auditRepository.getAuditHistoryForTransfer(t.id).single()
                        val src = entry.source
                        src?.apiSource?.jsonPath
                    }.toSet()
            assertEquals(4, jsonPaths.size, "Each transaction should have a distinct json_path")

            // --- Counterparty account audit ---
            // Every counterparty account created during import must also have an API entity source.
            // Previously these had no source, causing "Source data missing" in the audit UI.
            val counterpartyAccounts = allAccounts.filter { it.name.startsWith("Monzo Counterparty:") }
            assertTrue(counterpartyAccounts.isNotEmpty(), "Should have created counterparty accounts")
            counterpartyAccounts.forEach { counterparty ->
                val auditEntries = repositories.auditRepository.getAuditHistoryForAccount(counterparty.id)
                assertEquals(1, auditEntries.size, "${counterparty.name} should have exactly one audit entry")
                val cpAudit = auditEntries.single()
                val cpSource = cpAudit.source
                assertNotNull(cpSource, "${counterparty.name} audit must have a source — not 'Source data missing'")
                assertEquals(SourceType.API, cpSource.sourceType, "${counterparty.name} source should be API")
                val cpApiSource = cpSource.apiSource
                assertNotNull(cpApiSource, "${counterparty.name} must have API source details")
                assertEquals(sessionId, cpApiSource.sessionId)
                // The json_path points to the counterparty section of the transaction
                assert(cpApiSource.jsonPath.value.matches(Regex("""^\$\.transactions\[\d+\]\.counterparty$"""))) {
                    "${counterparty.name} json_path '${cpApiSource.jsonPath.value}' should be a $.transactions[N].counterparty path"
                }
            }
        }
}
