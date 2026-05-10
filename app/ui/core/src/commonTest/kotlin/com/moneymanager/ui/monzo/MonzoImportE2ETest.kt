@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.monzo

import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.SourceType
import com.moneymanager.rest.ApiSessionTrafficRecorder
import com.moneymanager.rest.createApiClient
import com.moneymanager.test.database.DbTest
import com.moneymanager.ui.api.discoverApiCounterpartiesToCreate
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

private val ACCOUNTS_WITH_SHARED_OWNER_ID_JSON =
    """
{
  "accounts": [
    {
      "id": "$ACCOUNT_ID",
      "closed": false,
      "created": "2022-01-01T00:00:00.000Z",
      "description": "$ACCOUNT_DESCRIPTION",
      "type": "uk_retail",
      "currency": "GBP",
      "owners": [
        { "user_id": "user_shared_001", "preferred_name": "Alice Example" }
      ]
    },
    {
      "id": "acc_00009TEST000000000099",
      "closed": false,
      "created": "2022-01-02T00:00:00.000Z",
      "description": "joint_00009TEST000000001",
      "type": "uk_retail",
      "currency": "GBP",
      "owners": [
        { "user_id": "user_shared_001", "preferred_name": "Alice Smith" }
      ]
    }
  ]
}
    """.trimIndent()

/**
 * Four anonymised transactions plus one declined transaction:
 *   tx1 – outgoing payment to a merchant  (amount negative, merchant.name set)
 *   tx2 – incoming payment from a person  (amount positive, counterparty.name set)
 *   tx3 – outgoing direct debit           (amount negative, description only)
 *   tx4 – zero-amount card verification   (amount zero)
 *   tx5 – DECLINED outgoing payment       (decline_reason set — must be skipped)
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
    },
    {
      "id": "tx_00009TEST000000000005",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-05-30T12:00:00.000Z",
      "amount": -30000,
      "currency": "GBP",
      "description": "DECLINED PAYMENT ATTEMPT",
      "decline_reason": "INVALID_PIN",
      "merchant": { "name": "Some Merchant" },
      "counterparty": {}
    }
  ]
}
    """.trimIndent()

private const val EMPTY_TRANSACTIONS_JSON = """{ "transactions": [] }"""

private val TRANSACTIONS_WITH_COUNTERPARTY_IDS_JSON =
    """
{
  "transactions": [
    {
      "id": "tx_00009TEST000000000020",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-06-04T09:15:00.000Z",
      "amount": -1250,
      "currency": "GBP",
      "description": "PAYMENT TO ALICE",
      "merchant": null,
      "counterparty": { "id": "cp_alice_001", "name": "Alice" }
    },
    {
      "id": "tx_00009TEST000000000021",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-06-05T09:15:00.000Z",
      "amount": 2500,
      "currency": "GBP",
      "description": "PAYMENT FROM ALICE SMITH",
      "merchant": null,
      "counterparty": { "id": "cp_alice_001", "name": "Alice Smith" }
    }
  ]
}
    """.trimIndent()

private val TRANSACTIONS_WITH_BANK_COUNTERPARTY_JSON =
    """
{
  "transactions": [
    {
      "id": "tx_00009TEST000000000030",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-06-04T09:15:00.000Z",
      "amount": -1250,
      "currency": "GBP",
      "description": "PAYWARD LTD",
      "merchant": null,
      "counterparty": { "account_number": "29900313", "name": "PAYWARD LTD", "sort_code": "041307" }
    },
    {
      "id": "tx_00009TEST000000000031",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-06-05T09:15:00.000Z",
      "amount": -2500,
      "currency": "GBP",
      "description": "Payward Ltd.",
      "merchant": null,
      "counterparty": { "account_number": "29900313", "name": "Payward Ltd.", "sort_code": "041307" }
    }
  ]
}
    """.trimIndent()

private val TRANSACTIONS_WITH_PERSONAL_COUNTERPARTY_JSON =
    """
{
  "transactions": [
    {
      "id": "tx_00009TEST000000000040",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-06-06T09:15:00.000Z",
      "amount": -1250,
      "currency": "GBP",
      "description": "Sent to John Doe",
      "merchant": null,
      "counterparty": {
        "account_number": "12345678",
        "beneficiary_account_type": "Personal",
        "name": "John Doe",
        "sort_code": "040404",
        "user_id": "anonuser_95515c2ea95c19a58aad7b"
      }
    },
    {
      "id": "tx_00009TEST000000000041",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-06-07T09:15:00.000Z",
      "amount": 2500,
      "currency": "GBP",
      "description": "Received from John Doe",
      "merchant": null,
      "counterparty": {
        "account_number": "87654321",
        "beneficiary_account_type": "Personal",
        "name": "John Q. Doe",
        "sort_code": "050505",
        "user_id": "anonuser_95515c2ea95c19a58aad7b"
      }
    }
  ]
}
    """.trimIndent()

private val TRANSACTIONS_WITH_ATM_WITHDRAWALS_JSON =
    """
{
  "transactions": [
    {
      "id": "tx_00009TEST000000000040",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-06-06T09:15:00.000Z",
      "amount": -20000,
      "currency": "GBP",
      "description": "3-5 QUEENS ROAD LONDON GBR",
      "category": "cash",
      "merchant": null,
      "counterparty": {},
      "atm_fees_detailed": { "fee_amount": 0, "withdrawal_amount": -20000, "withdrawal_currency": "GBP" }
    },
    {
      "id": "tx_00009TEST000000000041",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-06-07T10:00:00.000Z",
      "amount": -10000,
      "currency": "GBP",
      "description": "123 HIGH STREET LONDON GBR",
      "category": "cash",
      "merchant": null,
      "counterparty": {},
      "labels": ["withdrawal.atm.domestic-eea"]
    }
  ]
}
    """.trimIndent()

private val TRANSACTIONS_WITH_ATM_WITHDRAWALS_FOLLOW_UP_JSON =
    """
{
  "transactions": [
    {
      "id": "tx_00009TEST000000000042",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-06-08T11:00:00.000Z",
      "amount": -5000,
      "currency": "GBP",
      "description": "ATM CASH WITHDRAWAL",
      "category": "cash",
      "merchant": null,
      "counterparty": {},
      "metadata": { "mcc": "6011" }
    }
  ]
}
    """.trimIndent()

private val TRANSACTIONS_WITH_ATM_AND_COUNTERPARTY_IDS_JSON =
    """
{
  "transactions": [
    {
      "id": "tx_00009TEST000000000050",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-06-06T09:15:00.000Z",
      "amount": -20000,
      "currency": "GBP",
      "description": "BARCLAYS ATM LONDON",
      "category": "cash",
      "merchant": null,
      "counterparty": { "id": "atm_barclays_001" },
      "atm_fees_detailed": { "fee_amount": 0, "withdrawal_amount": -20000, "withdrawal_currency": "GBP" }
    },
    {
      "id": "tx_00009TEST000000000051",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-06-07T10:00:00.000Z",
      "amount": -10000,
      "currency": "GBP",
      "description": "NATWEST ATM MANCHESTER",
      "category": "cash",
      "merchant": null,
      "counterparty": { "id": "atm_natwest_001" },
      "labels": ["withdrawal.atm.domestic-eea"]
    }
  ]
}
    """.trimIndent()

/**
 * A page that contains only declined transactions — no settled tx at all.
 * Used to verify that an all-declined page does not prematurely stop pagination.
 */
private val DECLINED_ONLY_PAGE_JSON =
    """
{
  "transactions": [
    {
      "id": "tx_00009TEST000000000010",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-04-15T09:00:00.000Z",
      "amount": -5000,
      "currency": "GBP",
      "description": "DECLINED ATTEMPT 1",
      "decline_reason": "INSUFFICIENT_FUNDS",
      "merchant": { "name": "Shop A" },
      "counterparty": {}
    },
    {
      "id": "tx_00009TEST000000000011",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-04-14T10:00:00.000Z",
      "amount": -1000,
      "currency": "GBP",
      "description": "DECLINED ATTEMPT 2",
      "decline_reason": "INVALID_PIN",
      "merchant": { "name": "Shop B" },
      "counterparty": {}
    }
  ]
}
    """.trimIndent()

/**
 * A page with one settled transaction that follows the all-declined page.
 * The import must reach and process this page.
 */
private val SETTLED_AFTER_DECLINED_PAGE_JSON =
    """
{
  "transactions": [
    {
      "id": "tx_00009TEST000000000012",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-04-13T08:00:00.000Z",
      "amount": -2500,
      "currency": "GBP",
      "description": "SETTLED AFTER DECLINED",
      "merchant": { "name": "Shop C" },
      "counterparty": {}
    }
  ]
}
    """.trimIndent()

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
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single()

            // WHEN — download then import
            downloadApiSessionAccounts(
                token = "test-monzo-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
            )
            downloadApiSessionTransactions(
                token = "test-monzo-token",
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
                    transferSourceQueries = transferSourceQueries,
                    entitySourceQueries = repositories.entitySourceQueries,
                    personRepository = repositories.personRepository,
                    personAccountOwnershipRepository = repositories.personAccountOwnershipRepository,
                    personAttributeRepository = repositories.personAttributeRepository,
                    attributeTypeRepository = repositories.attributeTypeRepository,
                    accountAttributeRepository = repositories.accountAttributeRepository,
                    deviceId = deviceId,
                    sessionId = sessionId,
                    strategy = strategy,
                )

            // THEN — import counts (declined tx5 is stored but marked excluded)
            assertEquals(5, importResult.transactionCount, "Should have imported 5 transactions (declined tx stored as excluded)")
            assertEquals(1, importResult.excludedCount, "Declined tx5 should be counted as excluded")
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
            assertEquals(5, transfers.size, "Should have 5 transfers for the Monzo account (including declined tx stored as excluded)")

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
            assertEquals(5, jsonPaths.size, "Each transaction should have a distinct json_path")

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

    @Test
    fun `pagination continues past a page that contains only declined transactions`() =
        runTest {
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

            // Page sequence:
            //   page 1 (no before=)                         → all-declined page
            //   page 2 (before=2024-04-14T10:00:00.000Z)    → one settled transaction
            //   page 3 (before=2024-04-13T08:00:00.000Z)    → empty → stop
            var transactionRequestCount = 0
            val mockEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val json =
                        when {
                            url.contains("/accounts") -> ACCOUNTS_JSON
                            url.contains("/transactions") -> {
                                transactionRequestCount++
                                when (transactionRequestCount) {
                                    1 -> DECLINED_ONLY_PAGE_JSON
                                    2 -> SETTLED_AFTER_DECLINED_PAGE_JSON
                                    else -> EMPTY_TRANSACTIONS_JSON
                                }
                            }
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
                    .single()

            downloadApiSessionAccounts(
                token = "test-monzo-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
            )
            downloadApiSessionTransactions(
                token = "test-monzo-token",
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
                    transferSourceQueries = transferSourceQueries,
                    entitySourceQueries = repositories.entitySourceQueries,
                    personRepository = repositories.personRepository,
                    personAccountOwnershipRepository = repositories.personAccountOwnershipRepository,
                    personAttributeRepository = repositories.personAttributeRepository,
                    attributeTypeRepository = repositories.attributeTypeRepository,
                    accountAttributeRepository = repositories.accountAttributeRepository,
                    deviceId = deviceId,
                    sessionId = sessionId,
                    strategy = strategy,
                )

            // Pagination must have continued past the all-declined page to reach the settled tx
            assertEquals(3, transactionRequestCount, "Should have fetched 3 transaction pages (declined, settled, empty)")
            // 2 declined + 1 settled = 3 total imported; declined ones carry the "excluded" attribute
            assertEquals(3, importResult.transactionCount, "All 3 transactions should be imported (2 declined as excluded + 1 settled)")
            assertEquals(2, importResult.excludedCount, "The 2 declined transactions should be marked excluded")
            assertEquals(0, importResult.errorCount)

            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            val monzoAccount = allAccounts.single { it.name == "Monzo: $ACCOUNT_DESCRIPTION" }
            val transfers =
                repositories.transactionRepository
                    .getTransactionsByAccount(monzoAccount.id)
                    .first()
            assertEquals(3, transfers.size, "All 3 transactions (2 excluded + 1 settled) should be stored as transfers")
            val settledTransfer = transfers.single { it.attributes.none { attr -> attr.attributeType.name == "excluded" } }
            assertEquals(2500L, settledTransfer.amount.amount)
        }

    @Test
    fun `counterparty id creates one account attribute and prevents duplicate counterparty accounts`() =
        runTest {
            val deviceId =
                repositories.deviceRepository.getOrCreateDevice(
                    DeviceInfo.Jvm("test-machine", "Test OS"),
                )
            val sessionId =
                repositories.apiSessionRepository.createSession(
                    token = "test-monzo-token",
                    deviceId = deviceId,
                    createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                    expiresAt = null,
                )
            val mockEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val json =
                        when {
                            url.contains("/accounts") -> ACCOUNTS_JSON
                            url.contains("/transactions") && !url.contains("before=") -> TRANSACTIONS_WITH_COUNTERPARTY_IDS_JSON
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
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId, repositories.apiSessionRepository),
                    engine = mockEngine,
                )
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single()
                    .let { strategy ->
                        strategy.copy(
                            transactionMappings =
                                strategy.transactionMappings.copy(
                                    counterpartyIdField = "counterparty.id",
                                ),
                        )
                    }

            downloadApiSessionAccounts(
                token = "test-monzo-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
            )
            downloadApiSessionTransactions(
                token = "test-monzo-token",
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
                    transferSourceQueries = transferSourceQueries,
                    entitySourceQueries = repositories.entitySourceQueries,
                    personRepository = repositories.personRepository,
                    personAccountOwnershipRepository = repositories.personAccountOwnershipRepository,
                    personAttributeRepository = repositories.personAttributeRepository,
                    attributeTypeRepository = repositories.attributeTypeRepository,
                    accountAttributeRepository = repositories.accountAttributeRepository,
                    deviceId = deviceId,
                    sessionId = sessionId,
                    strategy = strategy,
                )

            assertEquals(2, importResult.transactionCount)
            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            val counterpartyAccounts = allAccounts.filter { it.name.startsWith("Monzo Counterparty:") }
            assertEquals(1, counterpartyAccounts.size, "The same counterparty.id should create one account")
            val counterpartyAttributes =
                counterpartyAccounts
                    .flatMap { account ->
                        repositories.accountAttributeRepository.getByAccount(account.id).first()
                    }.filter { it.attributeType.name == "account-external-id" }
            assertEquals(1, counterpartyAttributes.size, "account-external-id should be stored once as an account attribute")
            assertEquals("cp_alice_001", counterpartyAttributes.single().value)
        }

    @Test
    fun `account owners with same external user id create one person with person-external-id attribute`() =
        runTest {
            val deviceId =
                repositories.deviceRepository.getOrCreateDevice(
                    DeviceInfo.Jvm("test-machine", "Test OS"),
                )
            val sessionId =
                repositories.apiSessionRepository.createSession(
                    token = "test-monzo-token",
                    deviceId = deviceId,
                    createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                    expiresAt = null,
                )
            val mockEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val json =
                        when {
                            url.contains("/accounts") -> ACCOUNTS_WITH_SHARED_OWNER_ID_JSON
                            url.contains("/transactions") -> EMPTY_TRANSACTIONS_JSON
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
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId, repositories.apiSessionRepository),
                    engine = mockEngine,
                )
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single()

            downloadApiSessionAccounts(
                token = "test-monzo-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
            )
            downloadApiSessionTransactions(
                token = "test-monzo-token",
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
                    transferSourceQueries = transferSourceQueries,
                    entitySourceQueries = repositories.entitySourceQueries,
                    personRepository = repositories.personRepository,
                    personAccountOwnershipRepository = repositories.personAccountOwnershipRepository,
                    personAttributeRepository = repositories.personAttributeRepository,
                    attributeTypeRepository = repositories.attributeTypeRepository,
                    accountAttributeRepository = repositories.accountAttributeRepository,
                    deviceId = deviceId,
                    sessionId = sessionId,
                    strategy = strategy,
                )

            assertEquals(1, importResult.personCount)

            val allPeople = repositories.personRepository.getAllPeople().first()
            assertEquals(1, allPeople.size, "Same owner.user_id must map to one person even when names differ")
            val personExternalIdAttr =
                repositories.personAttributeRepository
                    .getByPerson(allPeople.single().id)
                    .first()
                    .single { it.attributeType.name == "person-external-id" }
            assertEquals("user_shared_001", personExternalIdAttr.value)

            val ownerships =
                repositories.personAccountOwnershipRepository
                    .getOwnershipsByPerson(allPeople.single().id)
                    .first()
            assertEquals(2, ownerships.size, "Shared owner.user_id should be linked to both imported accounts")
        }

    @Test
    fun `counterparty bank details are used when configured counterparty id is absent`() =
        runTest {
            val deviceId =
                repositories.deviceRepository.getOrCreateDevice(
                    DeviceInfo.Jvm("test-machine", "Test OS"),
                )
            val sessionId =
                repositories.apiSessionRepository.createSession(
                    token = "test-monzo-token",
                    deviceId = deviceId,
                    createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                    expiresAt = null,
                )
            val mockEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val json =
                        when {
                            url.contains("/accounts") -> ACCOUNTS_JSON
                            url.contains("/transactions") && !url.contains("before=") -> TRANSACTIONS_WITH_BANK_COUNTERPARTY_JSON
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
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId, repositories.apiSessionRepository),
                    engine = mockEngine,
                )
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single()
                    .let { strategy ->
                        strategy.copy(
                            transactionMappings =
                                strategy.transactionMappings.copy(
                                    counterpartyIdField = "counterparty.account_id",
                                ),
                        )
                    }

            downloadApiSessionAccounts(
                token = "test-monzo-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
            )
            downloadApiSessionTransactions(
                token = "test-monzo-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
            )

            importApiSessionTransactions(
                apiSessionRepository = repositories.apiSessionRepository,
                accountRepository = repositories.accountRepository,
                currencyRepository = repositories.currencyRepository,
                transactionRepository = repositories.transactionRepository,
                transferSourceQueries = transferSourceQueries,
                entitySourceQueries = repositories.entitySourceQueries,
                personRepository = repositories.personRepository,
                personAccountOwnershipRepository = repositories.personAccountOwnershipRepository,
                personAttributeRepository = repositories.personAttributeRepository,
                attributeTypeRepository = repositories.attributeTypeRepository,
                accountAttributeRepository = repositories.accountAttributeRepository,
                deviceId = deviceId,
                sessionId = sessionId,
                strategy = strategy,
            )

            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            val counterpartyAccounts = allAccounts.filter { it.name.startsWith("Monzo Counterparty:") }
            assertEquals(1, counterpartyAccounts.size, "Matching bank details should create one counterparty account")
            val counterpartyAttribute =
                repositories.accountAttributeRepository
                    .getByAccount(counterpartyAccounts.single().id)
                    .first()
                    .single { it.attributeType.name == "account-external-id" }
            assertEquals("bank:041307:29900313", counterpartyAttribute.value)
        }

    @Test
    fun `personal counterparties create one owner person keyed by external id`() =
        runTest {
            val deviceId =
                repositories.deviceRepository.getOrCreateDevice(
                    DeviceInfo.Jvm("test-machine", "Test OS"),
                )
            val sessionId =
                repositories.apiSessionRepository.createSession(
                    token = "test-monzo-token",
                    deviceId = deviceId,
                    createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                    expiresAt = null,
                )
            val mockEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val json =
                        when {
                            url.contains("/accounts") -> ACCOUNTS_JSON
                            url.contains("/transactions") && !url.contains("before=") -> TRANSACTIONS_WITH_PERSONAL_COUNTERPARTY_JSON
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
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId, repositories.apiSessionRepository),
                    engine = mockEngine,
                )
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single()
                    .let { strategy ->
                        strategy.copy(
                            transactionMappings =
                                strategy.transactionMappings.copy(
                                    counterpartyIdField = "counterparty.id",
                                ),
                        )
                    }

            downloadApiSessionAccounts(
                token = "test-monzo-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
            )
            downloadApiSessionTransactions(
                token = "test-monzo-token",
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
                    transferSourceQueries = transferSourceQueries,
                    entitySourceQueries = repositories.entitySourceQueries,
                    personRepository = repositories.personRepository,
                    personAccountOwnershipRepository = repositories.personAccountOwnershipRepository,
                    personAttributeRepository = repositories.personAttributeRepository,
                    attributeTypeRepository = repositories.attributeTypeRepository,
                    accountAttributeRepository = repositories.accountAttributeRepository,
                    deviceId = deviceId,
                    sessionId = sessionId,
                    strategy = strategy,
                )

            assertEquals(1, importResult.personCount)

            val people = repositories.personRepository.getAllPeople().first()
            val person = people.single()
            assertEquals("John Doe", person.fullName)
            val personExternalId =
                repositories.personAttributeRepository
                    .getByPerson(person.id)
                    .first()
                    .singleOrNull { it.attributeType.name == "person-external-id" }
                    ?: error("Expected person-external-id on imported person, but found none")
            assertEquals("anonuser_95515c2ea95c19a58aad7b", personExternalId.value)

            val ownerships =
                repositories.personAccountOwnershipRepository
                    .getOwnershipsByPerson(person.id)
                    .first()
            assertEquals(1, ownerships.size, "Expected the person to own exactly one counterparty account")
            val counterpartyAccount =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .singleOrNull { it.id == ownerships.single().accountId }
                    ?: error("Could not find the owned counterparty account")
            val counterpartyAttributes = repositories.accountAttributeRepository.getByAccount(counterpartyAccount.id).first()
            assertTrue(
                counterpartyAttributes.any {
                    it.attributeType.name == "account-sort-code" &&
                        it.value == "040404"
                },
                "Counterparty account should keep the personal sort code attribute: ${counterpartyAttributes.joinToString {
                    it.attributeType.name + '=' + it.value
                }}",
            )
            assertTrue(
                counterpartyAttributes.any {
                    it.attributeType.name == "account-account-number" &&
                        it.value == "12345678"
                },
                "Counterparty account should keep the personal account number attribute: ${counterpartyAttributes.joinToString {
                    it.attributeType.name + '=' + it.value
                }}",
            )
        }

    @Test
    fun `atm withdrawals with counterparty ids consolidate into one account and are excluded from discovery`() =
        runTest {
            val deviceId =
                repositories.deviceRepository.getOrCreateDevice(
                    DeviceInfo.Jvm("test-machine", "Test OS"),
                )
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single()
                    .let { strategy ->
                        strategy.copy(
                            transactionMappings =
                                strategy.transactionMappings.copy(
                                    counterpartyIdField = "counterparty.id",
                                ),
                        )
                    }
            val sessionId =
                repositories.apiSessionRepository.createSession(
                    token = "test-monzo-token",
                    deviceId = deviceId,
                    createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                    expiresAt = null,
                )
            val mockEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val json =
                        when {
                            url.contains("/accounts") -> ACCOUNTS_JSON
                            url.contains("/transactions") && !url.contains("before=") ->
                                TRANSACTIONS_WITH_ATM_AND_COUNTERPARTY_IDS_JSON
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
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId, repositories.apiSessionRepository),
                    engine = mockEngine,
                )

            downloadApiSessionAccounts(
                token = "test-monzo-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
            )
            downloadApiSessionTransactions(
                token = "test-monzo-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
            )

            val suggestions =
                discoverApiCounterpartiesToCreate(
                    apiSessionRepository = repositories.apiSessionRepository,
                    accountRepository = repositories.accountRepository,
                    accountAttributeRepository = repositories.accountAttributeRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                )
            assertEquals(0, suggestions.size, "ATM counterpartyIds must not appear in counterparty discovery")

            importApiSessionTransactions(
                apiSessionRepository = repositories.apiSessionRepository,
                accountRepository = repositories.accountRepository,
                currencyRepository = repositories.currencyRepository,
                transactionRepository = repositories.transactionRepository,
                transferSourceQueries = transferSourceQueries,
                entitySourceQueries = repositories.entitySourceQueries,
                personRepository = repositories.personRepository,
                personAccountOwnershipRepository = repositories.personAccountOwnershipRepository,
                personAttributeRepository = repositories.personAttributeRepository,
                attributeTypeRepository = repositories.attributeTypeRepository,
                accountAttributeRepository = repositories.accountAttributeRepository,
                deviceId = deviceId,
                sessionId = sessionId,
                strategy = strategy,
            )

            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            val atmAccounts =
                allAccounts.filter { account ->
                    repositories.accountAttributeRepository
                        .getByAccount(account.id)
                        .first()
                        .any { it.attributeType.name == "built-in type" && it.value == "ATM" }
                }
            assertEquals(1, atmAccounts.size, "All ATM withdrawals must consolidate into one built-in ATM account")
        }

    @Test
    fun `atm withdrawals reuse one built-in ATM counterparty account even after rename`() =
        runTest {
            val deviceId =
                repositories.deviceRepository.getOrCreateDevice(
                    DeviceInfo.Jvm("test-machine", "Test OS"),
                )
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single()

            suspend fun importAtmTransactions(
                sessionToken: String,
                transactionsJson: String,
            ) {
                val sessionId =
                    repositories.apiSessionRepository.createSession(
                        token = sessionToken,
                        deviceId = deviceId,
                        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                        expiresAt = null,
                    )
                val mockEngine =
                    MockEngine { request ->
                        val url = request.url.toString()
                        val json =
                            when {
                                url.contains("/accounts") -> ACCOUNTS_JSON
                                url.contains("/transactions") && !url.contains("before=") -> transactionsJson
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
                        trafficRecorder = ApiSessionTrafficRecorder(sessionId, repositories.apiSessionRepository),
                        engine = mockEngine,
                    )

                downloadApiSessionAccounts(
                    token = sessionToken,
                    apiClient = apiClient,
                    apiSessionRepository = repositories.apiSessionRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                )
                downloadApiSessionTransactions(
                    token = sessionToken,
                    apiClient = apiClient,
                    apiSessionRepository = repositories.apiSessionRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                )
                importApiSessionTransactions(
                    apiSessionRepository = repositories.apiSessionRepository,
                    accountRepository = repositories.accountRepository,
                    currencyRepository = repositories.currencyRepository,
                    transactionRepository = repositories.transactionRepository,
                    transferSourceQueries = transferSourceQueries,
                    entitySourceQueries = repositories.entitySourceQueries,
                    personRepository = repositories.personRepository,
                    personAccountOwnershipRepository = repositories.personAccountOwnershipRepository,
                    personAttributeRepository = repositories.personAttributeRepository,
                    attributeTypeRepository = repositories.attributeTypeRepository,
                    accountAttributeRepository = repositories.accountAttributeRepository,
                    deviceId = deviceId,
                    sessionId = sessionId,
                    strategy = strategy,
                )
            }

            importAtmTransactions(
                sessionToken = "test-monzo-token-1",
                transactionsJson = TRANSACTIONS_WITH_ATM_WITHDRAWALS_JSON,
            )

            val initialAtmAccount =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .firstOrNull { account ->
                        repositories.accountAttributeRepository
                            .getByAccount(account.id)
                            .first()
                            .any { it.attributeType.name == "built-in type" && it.value == "ATM" }
                    }
            assertNotNull(initialAtmAccount, "Expected built-in ATM account")
            val initialAtmAttributes = repositories.accountAttributeRepository.getByAccount(initialAtmAccount.id).first()
            assertEquals(
                "ATM",
                initialAtmAttributes.single { it.attributeType.name == "built-in type" }.value,
            )

            repositories.accountRepository.updateAccount(
                initialAtmAccount.copy(
                    name = "Monzo Counterparty: Renamed ATM",
                ),
            )

            importAtmTransactions(
                sessionToken = "test-monzo-token-2",
                transactionsJson = TRANSACTIONS_WITH_ATM_WITHDRAWALS_FOLLOW_UP_JSON,
            )

            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            val atmAccounts =
                allAccounts.filter { account ->
                    repositories.accountAttributeRepository
                        .getByAccount(account.id)
                        .first()
                        .any { it.attributeType.name == "built-in type" && it.value == "ATM" }
                }
            assertEquals(1, atmAccounts.size, "ATM built-in type should map withdrawals to one counterparty account")
            assertEquals("Monzo Counterparty: Renamed ATM", atmAccounts.first().name)
        }
}
