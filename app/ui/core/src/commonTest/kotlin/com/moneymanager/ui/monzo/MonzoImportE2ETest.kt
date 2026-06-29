@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.monzo
import com.moneymanager.apiimporter.discoverApiCounterpartiesToCreate
import com.moneymanager.apiimporter.downloadApiSessionAccounts
import com.moneymanager.apiimporter.downloadApiSessionTransactions
import com.moneymanager.apiimporter.importApiSessionTransactions
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.rest.ApiSessionTrafficRecorder
import com.moneymanager.rest.createApiClient
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createPerson
import com.moneymanager.test.database.updateAccount
import com.moneymanager.test.database.upsertCurrencyByCode
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

private val TRANSACTIONS_WITH_MIDDLE_NAME_COUNTERPARTY_JSON =
    """
{
  "transactions": [
    {
      "id": "tx_00009TEST000000000042",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-06-08T09:15:00.000Z",
      "amount": 1250,
      "currency": "GBP",
      "description": "Received from Nikolay",
      "merchant": null,
      "counterparty": {
        "account_number": "11223344",
        "beneficiary_account_type": "Personal",
        "name": "NIKOLAY IVANOV METCHEV",
        "sort_code": "060606",
        "user_id": "anonuser_middle_name_variant"
      }
    }
  ]
}
    """.trimIndent()

/**
 * Three outgoing payments to the SAME real person, each carrying a DIFFERENT throwaway `anonuser_…`
 * user id and NO bank details (no sort_code/account_number). The names differ only by casing and a
 * middle name. Because the ephemeral ids cannot key the account, all three must collapse onto one
 * counterparty account matched by the normalised name key.
 */
private val TRANSACTIONS_WITH_EPHEMERAL_COUNTERPARTY_JSON =
    """
{
  "transactions": [
    {
      "id": "tx_00009TEST000000000060",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-06-10T09:15:00.000Z",
      "amount": -1250,
      "currency": "GBP",
      "description": "Sent to Olga",
      "merchant": null,
      "counterparty": { "name": "Olga Zakharenko", "user_id": "anonuser_aaa", "beneficiary_account_type": "Personal" }
    },
    {
      "id": "tx_00009TEST000000000061",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-06-11T09:15:00.000Z",
      "amount": -2500,
      "currency": "GBP",
      "description": "Sent to OLGA",
      "merchant": null,
      "counterparty": { "name": "OLGA ZAKHARENKO", "user_id": "anonuser_bbb", "beneficiary_account_type": "Personal" }
    },
    {
      "id": "tx_00009TEST000000000062",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-06-12T09:15:00.000Z",
      "amount": -3750,
      "currency": "GBP",
      "description": "Sent to Olga M",
      "merchant": null,
      "counterparty": { "name": "Olga M Zakharenko", "user_id": "anonuser_ccc", "beneficiary_account_type": "Personal" }
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
 * A single ordinary (non-ATM) outgoing payment in the exact shape Monzo's real API returns: the
 * `atm_fees_detailed` key is present on every transaction but set to JSON `null` unless it is an
 * actual ATM withdrawal. The built-in ATM rule must NOT match this, or every payment becomes an ATM.
 */
private val TRANSACTION_WITH_NULL_ATM_FEES_JSON =
    """
{
  "transactions": [
    {
      "id": "tx_00009TEST000000000050",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-06-03T09:15:00.000Z",
      "amount": -1250,
      "currency": "GBP",
      "description": "COFFEE SHOP LTD LONDON GBR",
      "merchant": { "name": "Coffee Shop Ltd" },
      "counterparty": {},
      "metadata": {},
      "labels": null,
      "atm_fees_detailed": null
    }
  ]
}
    """.trimIndent()

/**
 * A foreign ATM withdrawal that carries a non-zero `atm_fees_detailed.fee_amount` (£3.50), the shape
 * Monzo returns when a cash withdrawal exceeds the fee-free allowance. The withdrawal itself is routed
 * to the consolidated ATM counterparty by the built-in rules; the fee must import as its own movement
 * into the "Monzo Fees" account, linked to the withdrawal via a `fee` relationship.
 */
private val TRANSACTION_WITH_ATM_FEE_JSON =
    """
{
  "transactions": [
    {
      "id": "tx_00009TEST_ATM_FEE_001",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-08-01T10:00:00.000Z",
      "amount": -20000,
      "currency": "GBP",
      "description": "FOREIGN ATM MADRID ESP",
      "category": "cash",
      "merchant": null,
      "counterparty": {},
      "atm_fees_detailed": { "fee_amount": 350, "withdrawal_amount": -20000, "withdrawal_currency": "GBP" }
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

/**
 * A page with two transactions containing local_amount / local_currency fields:
 *   tx_foreign – spent €14.47 abroad; account charged £12.50 (local != account currency)
 *   tx_domestic – a domestic GBP transaction where local_currency == currency
 *
 * Used to verify that the API importer uses the local amount/currency when they differ
 * from the account currency, matching the behaviour of the CSV import.
 */
private val TRANSACTIONS_WITH_LOCAL_CURRENCY_JSON =
    """
{
  "transactions": [
    {
      "id": "tx_00009TEST_FOREIGN_001",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-07-01T10:00:00.000Z",
      "amount": -1250,
      "currency": "GBP",
      "local_amount": -1447,
      "local_currency": "EUR",
      "description": "FOREIGN SPEND",
      "merchant": { "name": "Paris Cafe" },
      "counterparty": {}
    },
    {
      "id": "tx_00009TEST_DOMESTIC_001",
      "account_id": "$ACCOUNT_ID",
      "created": "2024-07-02T10:00:00.000Z",
      "amount": -5000,
      "currency": "GBP",
      "local_amount": -5000,
      "local_currency": "GBP",
      "description": "DOMESTIC SPEND",
      "merchant": { "name": "London Shop" },
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
                            importEngine = repositories.importEngine,
                        ),
                    engine = mockEngine,
                )
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Monzo" }

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
                    currencyRepository = repositories.currencyRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = repositories.importEngine,
                )

            // THEN — import counts (declined tx5 is stored but marked excluded)
            assertEquals(5, importResult.transactionCount, "Should have imported 5 transactions (declined tx stored as excluded)")
            assertEquals(1, importResult.excludedCount, "Declined tx5 should be counted as excluded")
            assertEquals(0, importResult.errorCount, "Should have no import errors")

            // --- Account audit ---
            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            val monzoAccount = allAccounts.single { it.name == ACCOUNT_DESCRIPTION }

            val accountAuditEntries = repositories.auditRepository.getAuditHistoryForAccount(monzoAccount.id)
            assertEquals(1, accountAuditEntries.size, "Monzo account should have exactly one audit entry (INSERT)")

            val accountAudit = accountAuditEntries.single()
            assertEquals(AuditType.INSERT, accountAudit.auditType)
            val accountSource = accountAudit.source
            assertNotNull(accountSource, "Account audit entry must have a source")
            val accountApiSource = accountSource.source
            assertIs<Source.Api>(accountApiSource, "Account source should be API")
            assertEquals(sessionId, accountApiSource.sessionId)
            assertEquals(JsonPath("$.accounts[0]"), accountApiSource.jsonPath)

            // The accounts list is request #1; its request_id must be present
            val accountsRequestId = accountApiSource.requestId
            assertNotNull(accountsRequestId, "Account source must reference the API request")

            // --- Transfer audit ---
            val transfers =
                repositories.transactionRepository
                    .getTransactionsByAccount(monzoAccount.id)
                    .first()
            assertEquals(5, transfers.size, "Should have 5 transfers for the Monzo account (including declined tx stored as excluded)")

            // Zero-amount transaction should use the Void counterparty account
            val voidAccount = allAccounts.find { it.name == "Void" }
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
                val txApiSource = txSource.source
                assertIs<Source.Api>(txApiSource, "Transfer $index source should be API")
                assertEquals(
                    sessionId,
                    txApiSource.sessionId,
                    "Transfer $index source session should match the download session",
                )
                val path = txApiSource.jsonPath?.value
                assertNotNull(path, "Transfer $index source must have a json_path")
                assert(path.startsWith("$.transactions[")) {
                    "Transfer $index json_path '$path' should be a $.transactions[N] path"
                }
            }

            // Verify each transaction maps to a distinct json_path
            val jsonPaths =
                transfers
                    .mapNotNull { t ->
                        val entry = repositories.auditRepository.getAuditHistoryForTransfer(t.id).single()
                        (entry.source?.source as? Source.Api)?.jsonPath
                    }.toSet()
            assertEquals(5, jsonPaths.size, "Each transaction should have a distinct json_path")

            // --- Counterparty account audit ---
            // Every counterparty account created during import must also have an API entity source.
            // Previously these had no source, causing "Source data missing" in the audit UI.
            val counterpartyAccounts = allAccounts.filter { it.id != monzoAccount.id }
            assertTrue(counterpartyAccounts.isNotEmpty(), "Should have created counterparty accounts")
            counterpartyAccounts.forEach { counterparty ->
                val auditEntries = repositories.auditRepository.getAuditHistoryForAccount(counterparty.id)
                assertEquals(1, auditEntries.size, "${counterparty.name} should have exactly one audit entry")
                val cpAudit = auditEntries.single()
                val cpSource = cpAudit.source
                assertNotNull(cpSource, "${counterparty.name} audit must have a source — not 'Source data missing'")
                val cpApiSource = cpSource.source
                assertIs<Source.Api>(cpApiSource, "${counterparty.name} source should be API")
                assertEquals(sessionId, cpApiSource.sessionId)
                // The json_path points to the counterparty section of the transaction
                val cpJsonPath = cpApiSource.jsonPath?.value
                assertNotNull(cpJsonPath, "${counterparty.name} must have API json_path details")
                assert(cpJsonPath.matches(Regex("""^\$\.transactions\[\d+]\.counterparty$"""))) {
                    "${counterparty.name} json_path '$cpJsonPath' should be a $.transactions[N].counterparty path"
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
                            importEngine = repositories.importEngine,
                        ),
                    engine = mockEngine,
                )
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Monzo" }

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
                    currencyRepository = repositories.currencyRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = repositories.importEngine,
                )

            // Pagination must have continued past the all-declined page to reach the settled tx
            assertEquals(3, transactionRequestCount, "Should have fetched 3 transaction pages (declined, settled, empty)")
            // 2 declined + 1 settled = 3 total imported; declined ones carry the "excluded" attribute
            assertEquals(3, importResult.transactionCount, "All 3 transactions should be imported (2 declined as excluded + 1 settled)")
            assertEquals(2, importResult.excludedCount, "The 2 declined transactions should be marked excluded")
            assertEquals(0, importResult.errorCount)

            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            val monzoAccount = allAccounts.single { it.name == ACCOUNT_DESCRIPTION }
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
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId, repositories.importEngine),
                    engine = mockEngine,
                )
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Monzo" }
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
                    currencyRepository = repositories.currencyRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = repositories.importEngine,
                )

            assertEquals(2, importResult.transactionCount)
            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            val monzoAccount = allAccounts.single { it.name == ACCOUNT_DESCRIPTION }
            val counterpartyAccounts = allAccounts.filter { it.id != monzoAccount.id }
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
    fun `account owners with same external user id create one person with monzo-external-id attribute`() =
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
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId, repositories.importEngine),
                    engine = mockEngine,
                )
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Monzo" }

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
                    currencyRepository = repositories.currencyRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = repositories.importEngine,
                )

            assertEquals(1, importResult.personCount)

            val allPeople = repositories.personRepository.getAllPeople().first()
            assertEquals(1, allPeople.size, "Same owner.user_id must map to one person even when names differ")
            val personExternalIdAttr =
                repositories.personAttributeRepository
                    .getByPerson(allPeople.single().id)
                    .first()
                    .single { it.attributeType.name == "monzo-external-id" }
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
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId, repositories.importEngine),
                    engine = mockEngine,
                )
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Monzo" }
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
                currencyRepository = repositories.currencyRepository,
                sessionId = sessionId,
                strategy = strategy,
                importEngine = repositories.importEngine,
            )

            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            val monzoAccount = allAccounts.single { it.name == ACCOUNT_DESCRIPTION }
            val counterpartyAccounts = allAccounts.filter { it.id != monzoAccount.id }
            assertEquals(1, counterpartyAccounts.size, "Matching bank details should create one counterparty account")
            val counterpartyAttrs =
                repositories.accountAttributeRepository
                    .getByAccount(counterpartyAccounts.single().id)
                    .first()
            assertEquals(
                "bank:041307:29900313",
                counterpartyAttrs.single { it.attributeType.name == "account-external-id" }.value,
            )
            // The bank identity is ALSO persisted as sort-code / account-number attributes (not only the
            // synthetic external-id), so a later import of another provider can bank-match this account by
            // attribute — this is what makes cross-provider reconciliation order-independent.
            assertEquals("041307", counterpartyAttrs.single { it.attributeType.name == "account-sort-code" }.value)
            assertEquals("29900313", counterpartyAttrs.single { it.attributeType.name == "account-account-number" }.value)
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
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId, repositories.importEngine),
                    engine = mockEngine,
                )
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Monzo" }
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
                    currencyRepository = repositories.currencyRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = repositories.importEngine,
                )
            assertEquals(2, importResult.transactionCount, "Should import both personal-counterparty transactions")
            assertEquals(1, importResult.personCount, "Should create one person for the shared external id")
            assertEquals(0, importResult.errorCount, "Should not produce import errors")

            val people = repositories.personRepository.getAllPeople().first()
            val matchingPeople =
                people.filter { person ->
                    repositories.personAttributeRepository
                        .getByPerson(person.id)
                        .first()
                        .any { it.attributeType.name == "monzo-external-id" && it.value == "anonuser_95515c2ea95c19a58aad7b" }
                }
            assertTrue(matchingPeople.isNotEmpty())
            val person = matchingPeople.first()
            assertEquals("John Doe", person.fullName)
            val personExternalId =
                repositories.personAttributeRepository
                    .getByPerson(person.id)
                    .first()
                    .singleOrNull { it.attributeType.name == "monzo-external-id" }
                    ?: error("Expected monzo-external-id on imported person, but found none")
            assertEquals("anonuser_95515c2ea95c19a58aad7b", personExternalId.value)

            val ownerships =
                repositories.personAccountOwnershipRepository
                    .getOwnershipsByPerson(person.id)
                    .first()
            assertEquals(2, ownerships.size, "Expected the person to own both counterparty accounts")
            val ownedAccounts =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .filter { it.id in ownerships.map { ownership -> ownership.accountId }.toSet() }
            assertEquals(2, ownedAccounts.size)
            val ownedAttributesByAccountId =
                ownedAccounts.associate { account ->
                    account.id to repositories.accountAttributeRepository.getByAccount(account.id).first()
                }
            assertTrue(
                ownedAttributesByAccountId.values.any { attrs ->
                    attrs.any {
                        it.attributeType.name == "account-sort-code" &&
                            it.value == "040404"
                    } &&
                        attrs.any {
                            it.attributeType.name == "account-account-number" &&
                                it.value == "12345678"
                        }
                },
                "Expected the first personal counterparty account attributes to be preserved",
            )
            assertTrue(
                ownedAttributesByAccountId.values.any { attrs ->
                    attrs.any {
                        it.attributeType.name == "account-sort-code" &&
                            it.value == "050505"
                    } &&
                        attrs.any {
                            it.attributeType.name == "account-account-number" &&
                                it.value == "87654321"
                        }
                },
                "Expected the second personal counterparty account attributes to be preserved",
            )
        }

    @Test
    fun `personal counterparty import matches existing person ignoring middle names`() =
        runTest {
            repositories.personRepository.createPerson(
                Person(
                    id = PersonId(0L),
                    firstName = "Nikolay",
                    middleName = null,
                    lastName = "Metchev",
                ),
            )
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
                            url.contains("/transactions") && !url.contains("before=") -> TRANSACTIONS_WITH_MIDDLE_NAME_COUNTERPARTY_JSON
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
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId, repositories.importEngine),
                    engine = mockEngine,
                )
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Monzo" }
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
                    currencyRepository = repositories.currencyRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = repositories.importEngine,
                )

            assertEquals(0, importResult.personCount, "Middle-name variants should match the existing first/last person")
            val people = repositories.personRepository.getAllPeople().first()
            assertEquals(1, people.size)
            assertEquals("Nikolay Metchev", people.single().fullName)
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
                    .single { it.name == "Monzo" }
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
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId, repositories.importEngine),
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
                currencyRepository = repositories.currencyRepository,
                sessionId = sessionId,
                strategy = strategy,
                importEngine = repositories.importEngine,
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
    fun `ordinary payments with a null atm_fees_detailed field are not routed to the ATM account`() =
        runTest {
            val deviceId =
                repositories.deviceRepository.getOrCreateDevice(
                    DeviceInfo.Jvm("test-machine", "Test OS"),
                )
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Monzo" }
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
                                TRANSACTION_WITH_NULL_ATM_FEES_JSON
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
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId, repositories.importEngine),
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
            val importResult =
                importApiSessionTransactions(
                    apiSessionRepository = repositories.apiSessionRepository,
                    currencyRepository = repositories.currencyRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = repositories.importEngine,
                )

            assertEquals(1, importResult.transactionCount, "Transaction should be imported")
            assertEquals(0, importResult.errorCount, "Should have no import errors")

            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            val atmAccounts =
                allAccounts.filter { account ->
                    repositories.accountAttributeRepository
                        .getByAccount(account.id)
                        .first()
                        .any { it.attributeType.name == "built-in type" && it.value == "ATM" }
                }
            assertEquals(0, atmAccounts.size, "A present-but-null atm_fees_detailed field must not create an ATM account")
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
                    .single { it.name == "Monzo" }

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
                        trafficRecorder = ApiSessionTrafficRecorder(sessionId, repositories.importEngine),
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
                    currencyRepository = repositories.currencyRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = repositories.importEngine,
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
                    .singleOrNull { account -> account.name.contains("ATM", ignoreCase = true) }
                    ?: error(
                        buildString {
                            appendLine("Expected ATM account")
                            appendLine("Imported accounts:")
                            repositories.accountRepository
                                .getAllAccounts()
                                .first()
                                .forEach { account ->
                                    val attributes =
                                        repositories.accountAttributeRepository
                                            .getByAccount(account.id)
                                            .first()
                                    appendLine("- ${account.name}: ${attributes.joinToString { it.attributeType.name + "=" + it.value }}")
                                }
                        },
                    )

            repositories.accountRepository.updateAccount(
                initialAtmAccount.copy(
                    name = "Renamed ATM",
                ),
            )

            importAtmTransactions(
                sessionToken = "test-monzo-token-2",
                transactionsJson = TRANSACTIONS_WITH_ATM_WITHDRAWALS_FOLLOW_UP_JSON,
            )

            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            val atmAccounts = allAccounts.filter { account -> account.name.contains("ATM", ignoreCase = true) }
            assertTrue(
                atmAccounts.size >= 2,
                "Expected the ATM import to preserve the renamed account and the follow-up import shape",
            )
            assertTrue(atmAccounts.any { it.name == "Renamed ATM" })
        }

    @Test
    fun `foreign currency transactions use local amount and local currency when configured`() =
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

            // Ensure EUR currency exists in the database
            repositories.currencyRepository.upsertCurrencyByCode("EUR", "Euro")

            val mockEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val json =
                        when {
                            url.contains("/accounts") -> ACCOUNTS_JSON
                            url.contains("/transactions") && !url.contains("before=") -> TRANSACTIONS_WITH_LOCAL_CURRENCY_JSON
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
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId, repositories.importEngine),
                    engine = mockEngine,
                )
            // Configure the strategy to use local_amount / local_currency fields
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Monzo" }
                    .let { baseStrategy ->
                        baseStrategy.copy(
                            transactionMappings =
                                baseStrategy.transactionMappings.copy(
                                    localAmountField = "local_amount",
                                    localCurrencyField = "local_currency",
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
                    currencyRepository = repositories.currencyRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = repositories.importEngine,
                )

            assertEquals(2, importResult.transactionCount, "Both transactions should be imported")
            assertEquals(0, importResult.errorCount, "Should have no import errors")

            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            val monzoAccount = allAccounts.single { it.name == ACCOUNT_DESCRIPTION }
            val transfers =
                repositories.transactionRepository
                    .getTransactionsByAccount(monzoAccount.id)
                    .first()
            assertEquals(2, transfers.size)

            // API import mirrors CSV semantics: transfer money remains in account currency.
            val foreignTransfer = transfers.single { it.description == "FOREIGN SPEND" }
            assertEquals("GBP", foreignTransfer.amount.currency.code, "Foreign transfer should remain in account currency")
            assertEquals(1250L, foreignTransfer.amount.amount, "Foreign transfer should use the main GBP amount")

            val domesticTransfer = transfers.single { it.description == "DOMESTIC SPEND" }
            assertEquals("GBP", domesticTransfer.amount.currency.code, "Domestic transfer should remain in account currency")
            assertEquals(5000L, domesticTransfer.amount.amount, "Domestic transfer should use the main GBP amount")
        }

    @Test
    fun `atm withdrawal fee imports as its own transfer linked to the withdrawal`() =
        runTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
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
                            url.contains("/transactions") && !url.contains("before=") -> TRANSACTION_WITH_ATM_FEE_JSON
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
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId, repositories.importEngine),
                    engine = mockEngine,
                )
            // Use the seeded Monzo strategy unchanged: it already maps the fee via atm_fees_detailed.fee_amount.
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Monzo" }

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
                    currencyRepository = repositories.currencyRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = repositories.importEngine,
                )

            // The fee is a separate movement, so it is not counted as an imported transaction.
            assertEquals(1, importResult.transactionCount, "Only the withdrawal counts as an imported transaction")
            assertEquals(0, importResult.errorCount, "Should have no import errors")

            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            val monzoAccount = allAccounts.single { it.name == ACCOUNT_DESCRIPTION }
            val feeAccount = allAccounts.single { it.name == "Monzo Fees" }

            // The Monzo account has two movements: the £200 withdrawal and the £3.50 fee.
            val transfers =
                repositories.transactionRepository
                    .getTransactionsByAccount(monzoAccount.id)
                    .first()
            assertEquals(2, transfers.size, "Withdrawal plus its fee")

            // Monzo's `amount` (-20000) is gross: it already includes the £3.50 fee, so the fee is carved
            // out (main 19650 + fee 350 = 20000) rather than double-charged.
            val withdrawal = transfers.single { it.description == "FOREIGN ATM MADRID ESP" }
            assertEquals(19650L, withdrawal.amount.amount, "Fee is carved out of the gross amount")

            val feeTransfer = transfers.single { it.description == "Fee" }
            assertEquals(350L, feeTransfer.amount.amount, "Fee transfer carries the atm fee amount")
            assertEquals("GBP", feeTransfer.amount.currency.code)
            assertEquals(monzoAccount.id, feeTransfer.sourceAccountId, "Fee leaves the Monzo account")
            assertEquals(feeAccount.id, feeTransfer.targetAccountId, "Fee goes to the consolidated Monzo Fees account")

            // The withdrawal (id1) links to the fee transfer (id2) via a `fee` relationship.
            val relationship =
                repositories.transferRelationshipRepository
                    .getByTransfer(withdrawal.id)
                    .first()
                    .single()
            assertEquals(withdrawal.id, relationship.id1)
            assertEquals(feeTransfer.id, relationship.id2)
            assertEquals("fee", relationship.relationshipType.name)

            // The fee's audit trail points at the JSON object holding the fee (atm_fees_detailed), not the
            // whole transaction nor the bare fee_amount leaf, so the user lands on that node in the viewer.
            val feeSource =
                repositories.auditRepository
                    .getAuditHistoryForTransfer(feeTransfer.id)
                    .single()
                    .source
            val feeApiSource = feeSource?.source
            assertIs<Source.Api>(feeApiSource)
            assertEquals("$.transactions[0].atm_fees_detailed", feeApiSource.jsonPath?.value)
        }

    @Test
    fun `ephemeral anonuser counterparties with the same name collapse into one account`() =
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
                            url.contains("/transactions") && !url.contains("before=") ->
                                TRANSACTIONS_WITH_EPHEMERAL_COUNTERPARTY_JSON
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
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId, repositories.importEngine),
                    engine = mockEngine,
                )
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Monzo" }

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
                    currencyRepository = repositories.currencyRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = repositories.importEngine,
                )
            assertEquals(3, importResult.transactionCount, "All three payments should import")
            assertEquals(0, importResult.errorCount, "Should have no import errors")

            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            val monzoAccount = allAccounts.single { it.name == ACCOUNT_DESCRIPTION }
            val counterpartyAccounts = allAccounts.filter { it.id != monzoAccount.id }
            assertEquals(
                1,
                counterpartyAccounts.size,
                "Ephemeral anonuser ids must not fragment one real person into multiple accounts: $counterpartyAccounts",
            )
        }
}
