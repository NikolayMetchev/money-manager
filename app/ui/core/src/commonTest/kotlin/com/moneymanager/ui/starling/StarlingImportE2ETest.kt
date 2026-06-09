@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.starling

import com.moneymanager.database.port.DbEntitySource
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.rest.ApiSessionTrafficRecorder
import com.moneymanager.rest.createApiClient
import com.moneymanager.test.database.DbTest
import com.moneymanager.ui.api.downloadApiSessionAccounts
import com.moneymanager.ui.api.downloadApiSessionPeople
import com.moneymanager.ui.api.downloadApiSessionTransactions
import com.moneymanager.ui.api.importApiSessionPeople
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

private const val ACCOUNT_UID = "11111111-1111-1111-1111-111111111111"
private const val CATEGORY_UID = "22222222-2222-2222-2222-222222222222"

private val ACCOUNTS_JSON =
    """
{
  "accounts": [
    {
      "accountUid": "$ACCOUNT_UID",
      "accountType": "PRIMARY",
      "defaultCategory": "$CATEGORY_UID",
      "currency": "GBP",
      "createdAt": "2021-01-01T00:00:00.000Z",
      "name": "Personal"
    }
  ]
}
    """.trimIndent()

/**
 * One outgoing and one incoming feed item. The direction (IN/OUT) — not the amount sign — determines
 * which side is the user's account; amounts are integer minor units.
 */
private val FEED_JSON =
    """
{
  "feedItems": [
    {
      "feedItemUid": "f1",
      "categoryUid": "$CATEGORY_UID",
      "amount": { "currency": "GBP", "minorUnits": 1250 },
      "direction": "OUT",
      "transactionTime": "2024-06-03T09:15:00.000Z",
      "reference": "COFFEE SHOP",
      "counterPartyName": "Coffee Shop"
    },
    {
      "feedItemUid": "f2",
      "categoryUid": "$CATEGORY_UID",
      "amount": { "currency": "GBP", "minorUnits": 50000 },
      "direction": "IN",
      "transactionTime": "2024-06-02T14:30:00.000Z",
      "reference": "SALARY",
      "counterPartyName": "ACME Ltd"
    }
  ]
}
    """.trimIndent()

/**
 * Two settled items plus one DECLINED item. Declined feed items never moved money, so they are
 * imported (for the record) but excluded from balances — mirroring Monzo's `decline_reason` handling.
 */
private val FEED_WITH_DECLINED_JSON =
    """
{
  "feedItems": [
    {
      "feedItemUid": "f1",
      "categoryUid": "$CATEGORY_UID",
      "amount": { "currency": "GBP", "minorUnits": 1250 },
      "direction": "OUT",
      "status": "SETTLED",
      "transactionTime": "2024-06-03T09:15:00.000Z",
      "reference": "COFFEE SHOP",
      "counterPartyName": "Coffee Shop"
    },
    {
      "feedItemUid": "f2",
      "categoryUid": "$CATEGORY_UID",
      "amount": { "currency": "GBP", "minorUnits": 50000 },
      "direction": "IN",
      "status": "SETTLED",
      "transactionTime": "2024-06-02T14:30:00.000Z",
      "reference": "SALARY",
      "counterPartyName": "ACME Ltd"
    },
    {
      "feedItemUid": "f3",
      "categoryUid": "$CATEGORY_UID",
      "amount": { "currency": "GBP", "minorUnits": 200000 },
      "direction": "OUT",
      "status": "DECLINED",
      "transactionTime": "2024-06-04T11:00:00.000Z",
      "reference": "CRYPTO.COM",
      "counterPartyName": "Crypto.com"
    }
  ]
}
    """.trimIndent()

private val ACCOUNT_HOLDER_JSON =
    """
{
  "title": "Ms",
  "firstName": "Ada",
  "lastName": "Lovelace",
  "dateOfBirth": "1990-01-01",
  "email": "ada@example.com"
}
    """.trimIndent()

class StarlingImportE2ETest : DbTest() {
    private val feedRequestUrls = mutableListOf<String>()

    private fun mockEngine(feedJson: String = FEED_JSON) =
        MockEngine { request ->
            val url = request.url.toString()
            val json =
                when {
                    url.contains("/account-holder/individual") -> ACCOUNT_HOLDER_JSON
                    url.contains("/feed/account/") -> {
                        feedRequestUrls += url
                        feedJson
                    }
                    url.contains("/api/v2/accounts") -> ACCOUNTS_JSON
                    else -> error("Unexpected request: $url")
                }
            respond(content = json, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }

    @Test
    fun `starling feed items import with direction-based sign and minor-unit amounts`() =
        runTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val sessionId = repositories.apiSessionRepository.createSession("test-starling-token", deviceId, now, null)
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Starling" }

            val apiClient =
                createApiClient(
                    trafficRecorder =
                        ApiSessionTrafficRecorder(sessionId = sessionId, apiSessionRepository = repositories.apiSessionRepository),
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

            assertEquals(2, importResult.transactionCount, "Both feed items should import")
            assertEquals(0, importResult.errorCount, "No import errors expected")

            // Starling's feed endpoint rejects requests without the mandatory `changesSince` bound
            // with HTTP 400, so every feed request must carry it.
            assertTrue(feedRequestUrls.isNotEmpty(), "A feed request should have been made")
            assertTrue(
                feedRequestUrls.all { it.contains("changesSince=") },
                "Every Starling feed request must include the mandatory changesSince parameter: $feedRequestUrls",
            )

            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            val ownAccount = allAccounts.single { it.name == "Starling: Personal" }
            val transfers = repositories.transactionRepository.getTransactionsByAccount(ownAccount.id).first()
            assertEquals(2, transfers.size)

            val coffee = allAccounts.single { it.name == "Starling Counterparty: Coffee Shop" }
            val acme = allAccounts.single { it.name == "Starling Counterparty: ACME Ltd" }

            val outgoing = transfers.single { it.amount.amount == 1250L }
            assertEquals(ownAccount.id, outgoing.sourceAccountId, "OUT direction: money leaves the user's account")
            assertEquals(coffee.id, outgoing.targetAccountId)

            val incoming = transfers.single { it.amount.amount == 50000L }
            assertEquals(acme.id, incoming.sourceAccountId)
            assertEquals(ownAccount.id, incoming.targetAccountId, "IN direction: money arrives at the user's account")
        }

    @Test
    fun `starling account holder is imported and linked to every account`() =
        runTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Starling" }

            fun clientFor(session: ApiSessionId) =
                createApiClient(
                    trafficRecorder =
                        ApiSessionTrafficRecorder(sessionId = session, apiSessionRepository = repositories.apiSessionRepository),
                    engine = mockEngine(),
                )

            // Import accounts (and transactions) so the Starling account exists.
            val accountsSessionId = repositories.apiSessionRepository.createSession("test-starling-token", deviceId, now, null)
            downloadApiSessionAccounts(
                token = "test-starling-token",
                apiClient = clientFor(accountsSessionId),
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = accountsSessionId,
                strategy = strategy,
            )
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
                sessionId = accountsSessionId,
                strategy = strategy,
            )

            // Download + import the single global account holder.
            val peopleSessionId = repositories.apiSessionRepository.createSession("test-starling-token", deviceId, now, null)
            val downloadResult =
                downloadApiSessionPeople(
                    token = "test-starling-token",
                    apiClient = clientFor(peopleSessionId),
                    apiSessionRepository = repositories.apiSessionRepository,
                    sessionId = peopleSessionId,
                    strategy = strategy,
                )
            assertEquals(1, downloadResult.personCount, "The single account holder object should be counted")

            val importResult =
                importApiSessionPeople(
                    apiSessionRepository = repositories.apiSessionRepository,
                    accountRepository = repositories.accountRepository,
                    accountAttributeRepository = repositories.accountAttributeRepository,
                    personRepository = repositories.personRepository,
                    personAccountOwnershipRepository = repositories.personAccountOwnershipRepository,
                    personAttributeRepository = repositories.personAttributeRepository,
                    attributeTypeRepository = repositories.attributeTypeRepository,
                    entitySource = DbEntitySource(repositories.entitySourceQueries, repositories.transferSourceQueries, deviceId),
                    sessionId = peopleSessionId,
                    strategy = strategy,
                    accountsSessionId = accountsSessionId,
                )
            assertEquals(1, importResult.personCount, "The account holder is created as a person")

            val people = repositories.personRepository.getAllPeople().first()
            val ada = people.single { it.firstName == "Ada" && it.lastName == "Lovelace" }
            val accounts = repositories.accountRepository.getAllAccounts().first()
            val ownAccount = accounts.single { it.name == "Starling: Personal" }
            val owners =
                repositories.personAccountOwnershipRepository
                    .getOwnershipsByAccount(ownAccount.id)
                    .first()
            assertTrue(owners.any { it.personId == ada.id }, "The account holder should own the Starling account")
            assertNotNull(ada)
        }

    @Test
    fun `declined starling feed items are imported but excluded from the account balance`() =
        runTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val sessionId = repositories.apiSessionRepository.createSession("test-starling-token", deviceId, now, null)
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Starling" }

            val apiClient =
                createApiClient(
                    trafficRecorder =
                        ApiSessionTrafficRecorder(sessionId = sessionId, apiSessionRepository = repositories.apiSessionRepository),
                    engine = mockEngine(feedJson = FEED_WITH_DECLINED_JSON),
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

            // All three items are imported, including the declined one (kept for the record).
            assertEquals(3, importResult.transactionCount, "All feed items import, declined included")

            // Balances are served from a materialized view that import does not refresh inline.
            repositories.maintenanceService.refreshMaterializedViews()

            val accounts = repositories.accountRepository.getAllAccounts().first()
            val ownAccount = accounts.single { it.name == "Starling: Personal" }
            val balances = repositories.transactionRepository.getAccountBalances().first()
            val ownBalance = balances.single { it.accountId == ownAccount.id }

            // Balance reflects only the two settled items (+50000 in, -1250 out); the 200000 declined
            // outgoing is excluded. Were it counted, the balance would be -151250 instead of 48750.
            assertEquals(48750L, ownBalance.balance.amount, "Declined item must not affect the balance")
        }
}
