@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.starling
import com.moneymanager.apiimporter.downloadApiSessionAccountIdentifiers
import com.moneymanager.apiimporter.downloadApiSessionAccounts
import com.moneymanager.apiimporter.downloadApiSessionPeople
import com.moneymanager.apiimporter.downloadApiSessionTransactions
import com.moneymanager.apiimporter.importApiSessionPeople
import com.moneymanager.apiimporter.importApiSessionTransactions
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.rest.ApiSessionTrafficRecorder
import com.moneymanager.rest.createApiClient
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.hasDisplayValue
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

/** The own account's bank details, as returned by Starling's per-account identifiers endpoint. */
private val IDENTIFIERS_JSON =
    """
{
  "accountIdentifier": "55556666",
  "bankIdentifier": "099999",
  "iban": "GB00SRLG09999955556666",
  "bic": "SRLGGB2L"
}
    """.trimIndent()

/**
 * One outgoing and one incoming feed item. The direction (IN/OUT) — not the amount sign — determines
 * which side is the user's account; amounts are integer minor units. The MERCHANT counterparty is not
 * a person; the SENDER one is, so it should gain a linked person/owner.
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
      "counterPartyType": "MERCHANT",
      "counterPartyUid": "cp-coffee",
      "counterPartyName": "Coffee Shop"
    },
    {
      "feedItemUid": "f2",
      "categoryUid": "$CATEGORY_UID",
      "amount": { "currency": "GBP", "minorUnits": 50000 },
      "direction": "IN",
      "transactionTime": "2024-06-02T14:30:00.000Z",
      "reference": "SALARY",
      "counterPartyType": "SENDER",
      "counterPartyUid": "cp-acme",
      "counterPartyName": "ACME Ltd",
      "counterPartySubEntityIdentifier": "040004",
      "counterPartySubEntitySubIdentifier": "12345678"
    }
  ]
}
    """.trimIndent()

/**
 * Two incoming feed items from what Starling reports as two different counterparties (distinct
 * counterPartyUid and counterPartyName) but the SAME bank account (sub-entity sort code + account
 * number). They must collapse into a single counterparty account, since the uid is per-payment.
 */
private val FEED_SAME_BANK_JSON =
    """
{
  "feedItems": [
    {
      "feedItemUid": "f1",
      "categoryUid": "$CATEGORY_UID",
      "amount": { "currency": "GBP", "minorUnits": 1000 },
      "direction": "IN",
      "transactionTime": "2024-06-02T14:30:00.000Z",
      "reference": "PAYMENT ONE",
      "counterPartyType": "SENDER",
      "counterPartyUid": "cp-one",
      "counterPartyName": "ACME Ltd",
      "counterPartySubEntityIdentifier": "040004",
      "counterPartySubEntitySubIdentifier": "12345678"
    },
    {
      "feedItemUid": "f2",
      "categoryUid": "$CATEGORY_UID",
      "amount": { "currency": "GBP", "minorUnits": 2000 },
      "direction": "IN",
      "transactionTime": "2024-06-03T14:30:00.000Z",
      "reference": "PAYMENT TWO",
      "counterPartyType": "SENDER",
      "counterPartyUid": "cp-two",
      "counterPartyName": "ACME Limited",
      "counterPartySubEntityIdentifier": "040004",
      "counterPartySubEntitySubIdentifier": "12345678"
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

/** A single incoming item from a SENDER counterparty whose name matches the account holder (Ada Lovelace). */
private val FEED_HOLDER_COUNTERPARTY_JSON =
    """
{
  "feedItems": [
    {
      "feedItemUid": "f1",
      "categoryUid": "$CATEGORY_UID",
      "amount": { "currency": "GBP", "minorUnits": 50000 },
      "direction": "IN",
      "transactionTime": "2024-06-02T14:30:00.000Z",
      "reference": "REPAYMENT",
      "counterPartyType": "SENDER",
      "counterPartyUid": "cp-ada",
      "counterPartyName": "Ada Lovelace"
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
    override val installBuiltInStrategies: Boolean = true

    // Pass a per-test list to capture feed request URLs; kept local so tests stay isolated.
    private fun mockEngine(
        feedJson: String = FEED_JSON,
        feedRequestUrls: MutableList<String>? = null,
        accountsJson: String = ACCOUNTS_JSON,
        identifiersJson: String? = null,
    ) = MockEngine { request ->
        val url = request.url.toString()
        val json =
            when {
                url.contains("/account-holder/individual") -> ACCOUNT_HOLDER_JSON
                url.contains("/identifiers") -> identifiersJson ?: error("Unexpected identifiers request: $url")
                url.contains("/feed/account/") -> {
                    feedRequestUrls?.add(url)
                    feedJson
                }
                url.contains("/api/v2/accounts") -> accountsJson
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

            val feedRequestUrls = mutableListOf<String>()
            val apiClient =
                createApiClient(
                    trafficRecorder =
                        ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
                    engine = mockEngine(feedRequestUrls = feedRequestUrls),
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
                    currencyRepository = repositories.currencyRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = repositories.importEngine,
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
            val ownAccount = allAccounts.single { it.name == "Personal" }
            val transfers = repositories.transactionRepository.getTransactionsByAccount(ownAccount.id).first()
            assertEquals(2, transfers.size)

            val coffee = allAccounts.single { it.name == "Coffee Shop" }
            val acme = allAccounts.single { it.name == "ACME Ltd" }

            val outgoing = transfers.single { it.amount.hasDisplayValue("12.50") }
            assertEquals(ownAccount.id, outgoing.sourceAccountId, "OUT direction: money leaves the user's account")
            assertEquals(coffee.id, outgoing.targetAccountId)

            val incoming = transfers.single { it.amount.hasDisplayValue("500.00") }
            assertEquals(acme.id, incoming.sourceAccountId)
            assertEquals(ownAccount.id, incoming.targetAccountId, "IN direction: money arrives at the user's account")
        }

    @Test
    fun `starling import sets identifying attributes and makes personal counterparties owners`() =
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
                        ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
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
            importApiSessionTransactions(
                apiSessionRepository = repositories.apiSessionRepository,
                currencyRepository = repositories.currencyRepository,
                sessionId = sessionId,
                strategy = strategy,
                importEngine = repositories.importEngine,
            )

            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            val ownAccount = allAccounts.single { it.name == "Personal" }
            val coffee = allAccounts.single { it.name == "Coffee Shop" }
            val acme = allAccounts.single { it.name == "ACME Ltd" }

            // Each transfer carries the feed item's stable id as an identifying attribute.
            val transfers = repositories.transactionRepository.getTransactionsByAccount(ownAccount.id).first()
            val txIds =
                transfers.map { t -> t.attributes.single { it.attributeType.name == "starling-transaction-id" }.value }
            assertEquals(setOf("f1", "f2"), txIds.toSet(), "Each transfer should record its feedItemUid")

            suspend fun attr(
                account: Account,
                typeName: String,
            ) = repositories.accountAttributeRepository
                .getByAccount(account.id)
                .first()
                .firstOrNull { it.attributeType.name == typeName }
                ?.value

            // The MERCHANT has no bank details, so its account is keyed by counterPartyUid.
            assertEquals("cp-coffee", attr(coffee, "account-external-id"))
            assertEquals(null, attr(coffee, "account-sort-code"))

            // The SENDER carries sub-entity bank details, which take precedence: the account is keyed
            // by its bank identity and the sort code + account number are stored as attributes.
            assertEquals("bank:040004:12345678", attr(acme, "account-external-id"))
            assertEquals("040004", attr(acme, "account-sort-code"))
            assertEquals("12345678", attr(acme, "account-account-number"))

            // Audit-trail origin points at the feed item itself — Starling's counterparty fields are
            // flat on the item, so there is no ".counterparty" node to expand in the API-traffic view.
            suspend fun originPath(account: Account): JsonPath? =
                repositories.auditRepository
                    .getAuditHistoryForAccount(account.id)
                    .first { it.auditType == AuditType.INSERT }
                    .source
                    ?.source
                    ?.let { it as? Source.Api }
                    ?.jsonPath
            assertEquals(JsonPath("$.feedItems[0]"), originPath(coffee))
            assertEquals(JsonPath("$.feedItems[1]"), originPath(acme))

            // The SENDER counterparty is a person and becomes an owner; the MERCHANT is not.
            val acmeOwners = repositories.personAccountOwnershipRepository.getOwnershipsByAccount(acme.id).first()
            assertEquals(1, acmeOwners.size, "The SENDER counterparty should gain a linked owner")
            val coffeeOwners = repositories.personAccountOwnershipRepository.getOwnershipsByAccount(coffee.id).first()
            assertTrue(coffeeOwners.isEmpty(), "The MERCHANT counterparty should not gain an owner")

            val acmePerson =
                repositories.personRepository
                    .getAllPeople()
                    .first()
                    .single { it.firstName == "ACME" }
            assertEquals(acmeOwners.single().personId, acmePerson.id)
        }

    @Test
    fun `starling counterparties sharing a bank account collapse into one despite different uids`() =
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
                        ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
                    engine = mockEngine(feedJson = FEED_SAME_BANK_JSON),
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
            importApiSessionTransactions(
                apiSessionRepository = repositories.apiSessionRepository,
                currencyRepository = repositories.currencyRepository,
                sessionId = sessionId,
                strategy = strategy,
                importEngine = repositories.importEngine,
            )

            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            val ownAccount = allAccounts.single { it.name == "Personal" }

            // Both feed items name a different counterparty (cp-one/"ACME Ltd" vs cp-two/"ACME Limited")
            // but the same bank account, so exactly one counterparty account must be created.
            val counterparties = allAccounts.filter { it.id != ownAccount.id }
            assertEquals(1, counterparties.size, "The shared bank account must yield a single counterparty: $counterparties")
            val counterparty = counterparties.single()

            val sortCode =
                repositories.accountAttributeRepository
                    .getByAccount(counterparty.id)
                    .first()
                    .single { it.attributeType.name == "account-sort-code" }
                    .value
            assertEquals("040004", sortCode)

            // Both incoming transfers arrive from that single counterparty account.
            val transfers = repositories.transactionRepository.getTransactionsByAccount(ownAccount.id).first()
            assertEquals(2, transfers.size)
            assertEquals(
                setOf(counterparty.id),
                transfers.map { it.sourceAccountId }.toSet(),
                "Both transfers should originate from the one deduped counterparty account",
            )
        }

    @Test
    fun `starling counterparty merges into a pre-existing account that shares its bank details`() =
        runTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

            // An account that already carries these bank details — e.g. your own account imported from
            // another provider. ACME in FEED_JSON has the same sort code + account number (040004/12345678).
            val existingId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Personal", openingDate = now),
                )
            repositories.accountAttributeRepository
                .insert(existingId, AttributeTypeId(WellKnownIds.ACCOUNT_SORT_CODE_ATTR_TYPE_ID), "040004")
            repositories.accountAttributeRepository
                .insert(existingId, AttributeTypeId(WellKnownIds.ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID), "12345678")

            val sessionId = repositories.apiSessionRepository.createSession("test-starling-token", deviceId, now, null)
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Starling" }
            val apiClient =
                createApiClient(
                    trafficRecorder =
                        ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
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
            importApiSessionTransactions(
                apiSessionRepository = repositories.apiSessionRepository,
                currencyRepository = repositories.currencyRepository,
                sessionId = sessionId,
                strategy = strategy,
                importEngine = repositories.importEngine,
            )

            val allAccounts = repositories.accountRepository.getAllAccounts().first()

            // No separate "ACME Ltd" counterparty account should be created — the counterparty
            // merges into the pre-existing account sharing 040004/12345678.
            assertTrue(
                allAccounts.none { it.name == "ACME Ltd" },
                "ACME must merge into the existing account, not create a counterparty duplicate: $allAccounts",
            )

            // The incoming ACME transfer originates from the pre-existing account.
            // The own account shares the bare name "Personal" with the pre-existing fixture account, and
            // account names are unique, so the own account (created second) is disambiguated. This test
            // doesn't stage the identifiers endpoint, so the own account carries no sort/account-number
            // attributes; the discriminator falls back to its external id (ACCOUNT_UID) last 6 chars.
            val ownAccount = allAccounts.single { it.name == "Personal (111111)" }
            val transfers = repositories.transactionRepository.getTransactionsByAccount(ownAccount.id).first()
            val acmeIncoming = transfers.single { it.amount.hasDisplayValue("500.00") }
            assertEquals(existingId, acmeIncoming.sourceAccountId, "The ACME transfer should link to the pre-existing account")
        }

    @Test
    fun `starling source account adopts a pre-existing account that shares its bank details`() =
        runTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

            // A counterparty another provider already created for this same bank account (the Starling own
            // account's identifiers report 099999/55556666). The Starling import must adopt it as its
            // source account rather than creating a duplicate — making cross-provider order irrelevant.
            val existingId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Nikolay", openingDate = now),
                )
            repositories.accountAttributeRepository
                .insert(existingId, AttributeTypeId(WellKnownIds.ACCOUNT_SORT_CODE_ATTR_TYPE_ID), "099999")
            repositories.accountAttributeRepository
                .insert(existingId, AttributeTypeId(WellKnownIds.ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID), "55556666")

            val sessionId = repositories.apiSessionRepository.createSession("test-starling-token", deviceId, now, null)
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Starling" }
            val apiClient =
                createApiClient(
                    trafficRecorder =
                        ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
                    engine = mockEngine(identifiersJson = IDENTIFIERS_JSON),
                )

            downloadApiSessionAccounts(
                token = "test-starling-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
            )
            downloadApiSessionAccountIdentifiers(
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
            importApiSessionTransactions(
                apiSessionRepository = repositories.apiSessionRepository,
                currencyRepository = repositories.currencyRepository,
                sessionId = sessionId,
                strategy = strategy,
                importEngine = repositories.importEngine,
            )

            val allAccounts = repositories.accountRepository.getAllAccounts().first()

            // The Starling source account IS the adopted pre-existing account (same id), now renamed; the
            // old counterparty name is gone and no second account carries 099999/55556666.
            val ownAccount = allAccounts.single { it.name == "Personal" }
            assertEquals(existingId, ownAccount.id, "The source account should reuse the pre-existing account's id")
            assertTrue(
                allAccounts.none { it.name == "Nikolay" },
                "The adopted account should have been renamed, not left as a duplicate: $allAccounts",
            )

            suspend fun hasBankDetails(account: Account): Boolean {
                val attrs = repositories.accountAttributeRepository.getByAccount(account.id).first()
                return attrs.any { it.attributeType.name == "account-sort-code" && it.value == "099999" }
            }
            assertEquals(
                listOf(existingId),
                allAccounts.filter { hasBankDetails(it) }.map { it.id },
                "Exactly one account should carry 099999/55556666",
            )

            // The adopted account gains the accounts-endpoint origin, so the audit trail can jump to its
            // "$.accounts[0]" source rather than only the counterparty row it was first created from.
            val originPaths =
                repositories.auditRepository
                    .getAuditHistoryForAccount(ownAccount.id)
                    .mapNotNull { (it.source?.source as? Source.Api)?.jsonPath }
            assertTrue(
                originPaths.contains(JsonPath("$.accounts[0]")),
                "Adopted source account should record the accounts-endpoint origin: $originPaths",
            )
        }

    @Test
    fun `starling source account adopts a pre-existing account identified only by a bank external-id`() =
        runTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

            // A counterparty another provider created for this same bank account (099999/55556666) whose only
            // identity is the synthetic "bank:<sort>:<account>" external-id — no sort-code/account-number
            // attributes. The bank-match index must still recognise it so the Starling source adopts it
            // rather than creating a duplicate, keeping cross-provider imports order-independent.
            val existingId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Nikolay", openingDate = now),
                )
            repositories.accountAttributeRepository
                .insert(existingId, AttributeTypeId(WellKnownIds.ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID), "bank:099999:55556666")

            val sessionId = repositories.apiSessionRepository.createSession("test-starling-token", deviceId, now, null)
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Starling" }
            val apiClient =
                createApiClient(
                    trafficRecorder =
                        ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
                    engine = mockEngine(identifiersJson = IDENTIFIERS_JSON),
                )

            downloadApiSessionAccounts(
                token = "test-starling-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
            )
            downloadApiSessionAccountIdentifiers(
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
            importApiSessionTransactions(
                apiSessionRepository = repositories.apiSessionRepository,
                currencyRepository = repositories.currencyRepository,
                sessionId = sessionId,
                strategy = strategy,
                importEngine = repositories.importEngine,
            )

            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            val ownAccount = allAccounts.single { it.name == "Personal" }
            assertEquals(existingId, ownAccount.id, "The source account should adopt the bank-external-id account")
            assertTrue(
                allAccounts.none { it.name == "Nikolay" },
                "The adopted account should have been renamed, not left as a duplicate: $allAccounts",
            )
        }

    @Test
    fun `starling source account gets its bank details from the identifiers endpoint`() =
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
                        ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
                    engine = mockEngine(identifiersJson = IDENTIFIERS_JSON),
                )

            downloadApiSessionAccounts(
                token = "test-starling-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
            )
            downloadApiSessionAccountIdentifiers(
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
            importApiSessionTransactions(
                apiSessionRepository = repositories.apiSessionRepository,
                currencyRepository = repositories.currencyRepository,
                sessionId = sessionId,
                strategy = strategy,
                importEngine = repositories.importEngine,
            )

            // The own account carries the sort code + account number returned by the identifiers endpoint,
            // so cross-provider counterparties for this same account can later match and merge into it.
            val ownAccount =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .single { it.name == "Personal" }
            val attrs = repositories.accountAttributeRepository.getByAccount(ownAccount.id).first()
            assertEquals("099999", attrs.single { it.attributeType.name == "account-sort-code" }.value)
            assertEquals("55556666", attrs.single { it.attributeType.name == "account-account-number" }.value)
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

            // One session holds accounts, transactions and the account holder.
            val sessionId = repositories.apiSessionRepository.createSession("test-starling-token", deviceId, now, null)
            val apiClient =
                createApiClient(
                    trafficRecorder =
                        ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
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
            val downloadResult =
                downloadApiSessionPeople(
                    token = "test-starling-token",
                    apiClient = apiClient,
                    apiSessionRepository = repositories.apiSessionRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                )
            assertEquals(1, downloadResult.personCount, "The single account holder object should be counted")

            // Mirrors the UI's combined import: transactions first (creates accounts), then people.
            importApiSessionTransactions(
                apiSessionRepository = repositories.apiSessionRepository,
                currencyRepository = repositories.currencyRepository,
                sessionId = sessionId,
                strategy = strategy,
                importEngine = repositories.importEngine,
            )
            importApiSessionPeople(
                apiSessionRepository = repositories.apiSessionRepository,
                accountRepository = repositories.accountRepository,
                accountAttributeRepository = repositories.accountAttributeRepository,
                importEngine = repositories.importEngine,
                sessionId = sessionId,
                strategy = strategy,
                accountsSessionId = sessionId,
            )

            val people = repositories.personRepository.getAllPeople().first()
            val ada = people.single { it.firstName == "Ada" && it.lastName == "Lovelace" }
            val accounts = repositories.accountRepository.getAllAccounts().first()
            val ownAccount = accounts.single { it.name == "Personal" }
            val owners =
                repositories.personAccountOwnershipRepository
                    .getOwnershipsByAccount(ownAccount.id)
                    .first()
            assertTrue(owners.any { it.personId == ada.id }, "The account holder should own the Starling account")
            assertNotNull(ada)
        }

    @Test
    fun `account holder owns the own account even when people are imported before accounts`() =
        runTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Starling" }

            // Accounts and the holder are downloaded into one session; importing people before the
            // transactions still ends with the holder owning the own account.
            val sessionId = repositories.apiSessionRepository.createSession("test-starling-token", deviceId, now, null)
            val apiClient =
                createApiClient(
                    trafficRecorder =
                        ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
                    engine = mockEngine(),
                )
            downloadApiSessionAccounts(
                token = "test-starling-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
            )
            downloadApiSessionPeople(
                token = "test-starling-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
            )
            // An own account with no transfers referencing it is never created (see
            // BatchAccountResolver.pruneUnreferencedSourceAccounts), so the feed must be downloaded too.
            downloadApiSessionTransactions(
                token = "test-starling-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
            )

            // 1) People FIRST, before any accounts exist — the holder is created but can link to nothing.
            importApiSessionPeople(
                apiSessionRepository = repositories.apiSessionRepository,
                accountRepository = repositories.accountRepository,
                accountAttributeRepository = repositories.accountAttributeRepository,
                importEngine = repositories.importEngine,
                sessionId = sessionId,
                strategy = strategy,
                accountsSessionId = sessionId,
            )

            // 2) Accounts AFTER the holder — the transactions import back-links the holder to the own
            // account by reading the people responses from the same session.
            importApiSessionTransactions(
                apiSessionRepository = repositories.apiSessionRepository,
                currencyRepository = repositories.currencyRepository,
                sessionId = sessionId,
                strategy = strategy,
                importEngine = repositories.importEngine,
            )

            val ada =
                repositories.personRepository
                    .getAllPeople()
                    .first()
                    .single { it.firstName == "Ada" && it.lastName == "Lovelace" }
            val ownAccount =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .single { it.name == "Personal" }
            val owners = repositories.personAccountOwnershipRepository.getOwnershipsByAccount(ownAccount.id).first()
            assertTrue(owners.any { it.personId == ada.id }, "The holder should own the own account regardless of import order")
        }

    @Test
    fun `backfilling a matched person external id does not create an orphan revision`() =
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
                        ApiSessionTrafficRecorder(sessionId = session, importEngine = repositories.importEngine),
                    engine = mockEngine(feedJson = FEED_HOLDER_COUNTERPARTY_JSON),
                )

            // Import the holder first — "Ada Lovelace" is created at revision 1 with no Starling external id.
            val peopleSessionId = repositories.apiSessionRepository.createSession("test-starling-token", deviceId, now, null)
            downloadApiSessionPeople(
                token = "test-starling-token",
                apiClient = clientFor(peopleSessionId),
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = peopleSessionId,
                strategy = strategy,
            )
            importApiSessionPeople(
                apiSessionRepository = repositories.apiSessionRepository,
                accountRepository = repositories.accountRepository,
                accountAttributeRepository = repositories.accountAttributeRepository,
                importEngine = repositories.importEngine,
                sessionId = peopleSessionId,
                strategy = strategy,
            )

            // Importing the SENDER counterparty "Ada Lovelace" matches the holder by name and backfills
            // its external id — this must NOT bump the person to a new (source-less) revision.
            val txSessionId = repositories.apiSessionRepository.createSession("test-starling-token", deviceId, now, null)
            downloadApiSessionAccounts(
                token = "test-starling-token",
                apiClient = clientFor(txSessionId),
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = txSessionId,
                strategy = strategy,
            )
            downloadApiSessionTransactions(
                token = "test-starling-token",
                apiClient = clientFor(txSessionId),
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = txSessionId,
                strategy = strategy,
            )
            importApiSessionTransactions(
                apiSessionRepository = repositories.apiSessionRepository,
                currencyRepository = repositories.currencyRepository,
                sessionId = txSessionId,
                strategy = strategy,
                importEngine = repositories.importEngine,
            )

            val ada =
                repositories.personRepository
                    .getAllPeople()
                    .first()
                    .single { it.firstName == "Ada" && it.lastName == "Lovelace" }
            assertEquals(1L, ada.revisionId, "Backfilling an external id must not bump the person revision")
            // The backfill still happened: the external id is now stored.
            val externalId =
                repositories.personAttributeRepository
                    .getByPerson(ada.id)
                    .first()
                    .single { it.attributeType.name == "starling-external-id" }
                    .value
            assertEquals("cp-ada", externalId)
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
                        ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
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
                    currencyRepository = repositories.currencyRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = repositories.importEngine,
                )

            // All three items are imported, including the declined one (kept for the record).
            assertEquals(3, importResult.transactionCount, "All feed items import, declined included")

            // Balances are served from a materialized view that import does not refresh inline.
            repositories.maintenanceService.refreshMaterializedViews()

            val accounts = repositories.accountRepository.getAllAccounts().first()
            val ownAccount = accounts.single { it.name == "Personal" }
            val balances = repositories.transactionRepository.getAccountBalances().first()
            val ownBalance = balances.single { it.accountId == ownAccount.id }

            // Balance reflects only the two settled items (+500.00 in, -12.50 out); the 2000.00 declined
            // outgoing is excluded. Were it counted, the balance would be -1512.50 instead of 487.50.
            assertTrue(
                ownBalance.balance.hasDisplayValue("487.50"),
                "Declined item must not affect the balance",
            )
        }
}
