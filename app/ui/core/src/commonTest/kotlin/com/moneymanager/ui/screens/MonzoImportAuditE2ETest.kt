@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import com.moneymanager.apiimporter.downloadApiSessionAccounts
import com.moneymanager.apiimporter.downloadApiSessionTransactions
import com.moneymanager.apiimporter.importApiSessionTransactions
import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.di.DatabaseComponent
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.rest.ApiSessionTrafficRecorder
import com.moneymanager.rest.createApiClient
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.test.database.installBuiltInApiStrategies
import com.moneymanager.ui.test.MoneyManagerTestApp
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Instant

// Anonymised Monzo account and transaction fixtures — identical data to MonzoImportE2ETest.
private const val AUDIT_E2E_ACCOUNT_ID = "acc_00009TEST000000000001"
private const val AUDIT_E2E_ACCOUNT_DESCRIPTION = "user_00009TEST000000user"

private val AUDIT_E2E_ACCOUNTS_JSON =
    """
{
  "accounts": [
    {
      "id": "$AUDIT_E2E_ACCOUNT_ID",
      "closed": false,
      "created": "2022-01-01T00:00:00.000Z",
      "description": "$AUDIT_E2E_ACCOUNT_DESCRIPTION",
      "type": "uk_retail",
      "currency": "GBP"
    }
  ]
}
    """.trimIndent()

private val AUDIT_E2E_TRANSACTIONS_JSON =
    """
{
  "transactions": [
    {
      "id": "tx_00009TEST000000000001",
      "account_id": "$AUDIT_E2E_ACCOUNT_ID",
      "created": "2024-06-03T09:15:00.000Z",
      "amount": -1250,
      "currency": "GBP",
      "description": "COFFEE SHOP LTD LONDON GBR",
      "merchant": { "name": "Coffee Shop Ltd" },
      "counterparty": {}
    },
    {
      "id": "tx_00009TEST000000000002",
      "account_id": "$AUDIT_E2E_ACCOUNT_ID",
      "created": "2024-06-02T14:30:00.000Z",
      "amount": 50000,
      "currency": "GBP",
      "description": "Sent from Alice",
      "merchant": null,
      "counterparty": { "name": "Alice Example" }
    },
    {
      "id": "tx_00009TEST000000000003",
      "account_id": "$AUDIT_E2E_ACCOUNT_ID",
      "created": "2024-06-01T08:00:00.000Z",
      "amount": -9999,
      "currency": "GBP",
      "description": "EXAMPLE DIRECT DEBIT",
      "merchant": null,
      "counterparty": {}
    },
    {
      "id": "tx_00009TEST000000000004",
      "account_id": "$AUDIT_E2E_ACCOUNT_ID",
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

/**
 * UI end-to-end test for Monzo account audit history.
 *
 * Pre-populates the database with a full download + import (same anonymised data as
 * MonzoImportE2ETest), then drives the UI to verify:
 *   1. The imported Monzo account shows "API Import" as the source in its audit history.
 *   2. Clicking "API Import" navigates to the API Traffic screen for that session.
 *
 * Account ordering note: the DB orders accounts by name (SQLite binary collation). Alphabetically
 * among the fixture's accounts — "Alice Example", "Coffee Shop Ltd", "Monzo" (the own account, named
 * via the strategy's staticAccountName), "Void" — the own Monzo account is third, at index 2.
 */
private class AuditTestDatabaseManager(
    private val databaseManager: DatabaseManager,
    private val testLocation: DbLocation,
) : DatabaseManager by databaseManager {
    override fun getDefaultLocation(): DbLocation = testLocation
}

@OptIn(ExperimentalTestApi::class)
class MonzoImportAuditE2ETest {
    private var testDbLocation: DbLocation? = null

    @AfterTest
    fun cleanup() {
        testDbLocation?.let { deleteTestDatabase(it) }
    }

    @Test
    fun `imported account audit history shows API Import source and navigates to API traffic`() =
        runMoneyManagerComposeUiTest {
            // Pre-populate: run the full download + import before the UI launches
            testDbLocation = createTestDatabaseLocation()
            val databaseManager = createTestDatabaseManager()

            runBlocking {
                val db = databaseManager.openDatabase(testDbLocation!!)
                val dc = DatabaseComponent.create(db)
                dc.installBuiltInApiStrategies()

                val deviceId =
                    dc.deviceRepository.getOrCreateDevice(
                        DeviceInfo.Jvm("test-machine", "Test OS"),
                    )
                val sessionId =
                    dc.apiSessionRepository.createSession(
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
                                url.contains("/accounts") -> AUDIT_E2E_ACCOUNTS_JSON
                                url.contains("/transactions") && !url.contains("before=") -> AUDIT_E2E_TRANSACTIONS_JSON
                                else -> """{ "transactions": [] }"""
                            }
                        respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }

                val apiClient =
                    createApiClient(
                        trafficRecorder = ApiSessionTrafficRecorder(sessionId, dc.importEngine),
                        engine = mockEngine,
                    )
                val strategy =
                    dc.apiImportStrategyRepository
                        .getStrategyByName("Monzo")
                        .first()
                        ?: error("Monzo strategy not found")

                downloadApiSessionAccounts(
                    token = "test-monzo-token",
                    apiClient = apiClient,
                    apiSessionRepository = dc.apiSessionRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                )
                downloadApiSessionTransactions(
                    token = "test-monzo-token",
                    apiClient = apiClient,
                    apiSessionRepository = dc.apiSessionRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                )
                importApiSessionTransactions(
                    apiSessionRepository = dc.apiSessionRepository,
                    currencyRepository = dc.currencyRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = dc.importEngine,
                )
            }

            val testDatabaseManager =
                AuditTestDatabaseManager(
                    databaseManager = databaseManager,
                    testLocation = testDbLocation!!,
                )

            setContent {
                MoneyManagerTestApp(
                    databaseManager = testDatabaseManager,
                    appVersion = AppVersion("1.0.0-test"),
                )
            }

            // Wait for the Accounts screen to load
            waitForIdle()
            val monzoAccountName = "Monzo"
            waitUntilAtLeastOneExists(hasText(monzoAccountName), timeoutMillis = 20000)

            // --- Part 1: Monzo account audit shows API Import ---
            // Alphabetically third among the four fixture accounts (see class doc) — index 2.
            onAllNodesWithText("📋")[2].performClick()
            waitForIdle()

            waitUntilAtLeastOneExists(hasText("API Import"), timeoutMillis = 10000)
            waitUntilDoesNotExist(hasText("Source data missing"), timeoutMillis = 3000)

            onNodeWithText("API Import").performClick()
            waitForIdle()
            waitUntilAtLeastOneExists(hasText("API Traffic"), timeoutMillis = 10000)

            // Navigate back to Accounts via the nav bar
            onNodeWithText("Accounts", useUnmergedTree = true).performClick()
            waitForIdle()
            waitUntilAtLeastOneExists(hasText(monzoAccountName), timeoutMillis = 10000)

            // --- Part 2: Counterparty account audit also shows API Import (this was the real bug) ---
            // "Coffee Shop Ltd" is a counterparty — click its audit button.
            val coffeeShopName = "Coffee Shop Ltd"
            waitUntilAtLeastOneExists(hasText(coffeeShopName), timeoutMillis = 5000)
            // The counterparties (uppercase initials) all sort before the own "user_..." account, so the
            // first 📋 in the list belongs to a counterparty ("Alice Example") — click it.
            onAllNodesWithText("📋").onFirst().performClick()
            waitForIdle()

            // Previously showed "Source data missing" — now must show "API Import"
            waitUntilAtLeastOneExists(hasText("API Import"), timeoutMillis = 10000)
            waitUntilDoesNotExist(hasText("Source data missing"), timeoutMillis = 3000)
        }
}
