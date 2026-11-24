package com.moneymanager.apiimporter

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiPeopleMappings
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ApiImportHelpersTest {
    @Test
    fun `download summaries say what was fetched or skipped`() {
        assertEquals("Accounts downloaded: 2 account(s).", ApiAccountsDownloadResult(2).displaySummary())
        assertEquals(
            "Accounts already downloaded: 2 account(s) (skipped).",
            ApiAccountsDownloadResult(2, skipped = true).displaySummary(),
        )
        assertEquals("People downloaded: 3 person(s).", ApiPeopleDownloadResult(3).displaySummary())
        assertEquals(
            "People already downloaded: 3 person(s) (skipped).",
            ApiPeopleDownloadResult(3, skipped = true).displaySummary(),
        )
    }

    @Test
    fun `an incremental transaction download names the date it started from`() {
        val summary =
            ApiTransactionsDownloadResult(1, 4, Instant.parse("2025-03-02T10:00:00Z")).displaySummary()

        assertEquals(
            "Transactions downloaded: 1 account(s), 4 new response page(s). Incremental: fetched from 2025-03-02 onwards.",
            summary,
        )
    }

    @Test
    fun `the session summary joins the parts that were downloaded`() {
        val result =
            ApiSessionDownloadResult(
                accounts = ApiAccountsDownloadResult(1),
                transactions = ApiTransactionsDownloadResult(1, 2),
                people = ApiPeopleDownloadResult(5),
            )

        assertEquals(3, result.displaySummary().lines().size)
        assertEquals(1, ApiSessionDownloadResult(ApiAccountsDownloadResult(1), null, null).displaySummary().lines().size)
    }

    @Test
    fun `a bank counterparty id yields its dedupe key and only that`() {
        assertEquals("200000|12345678", bankDedupeKeyFromCounterpartyId("bank:200000:12345678"))
        assertNull(bankDedupeKeyFromCounterpartyId("user:abc"))
        assertNull(bankDedupeKeyFromCounterpartyId(null))
        assertNull(bankDedupeKeyFromCounterpartyId("bank:200000"))
        assertNull(bankDedupeKeyFromCounterpartyId("bank::12345678"))
    }

    @Test
    fun `a dedupe key splits back into sort code and account number`() {
        assertEquals("200000" to "12345678", bankIdentityFromDedupeKey("200000|12345678"))
        assertNull(bankIdentityFromDedupeKey("200000"))
        assertNull(bankIdentityFromDedupeKey("|12345678"))
        assertNull(bankIdentityFromDedupeKey(null))
    }

    @Test
    fun `url expressions resolve from the window and the ancestors`() {
        val context =
            ImportUrlContext(
                ancestorVars = mapOf("acc.id" to "A1", "other" to "x"),
                windowStart = "2025-01-01",
                windowEnd = "2025-02-01",
            )

        assertEquals("2025-01-01", context.resolve("window.start"))
        assertEquals("2025-02-01", context.resolve("window.end"))
        assertEquals("A1", context.resolve("parent.id"))
        assertEquals("x", context.resolve("other"))
        assertNull(context.resolve("account.id"))
        assertNull(context.resolve("account.name"))
        assertNull(context.resolve("missing"))
    }

    @Test
    fun `an error array or a wrong code fails the envelope check`() {
        assertTrue(responseCodeOk("""{"code":"0"}""", "code", "0"))
        assertFalse(responseCodeOk("""{"code":"1"}""", "code", "0"))
        assertFalse(responseCodeOk("""{"error":["boom"]}""", null, null, errorArrayField = "error"))
        assertTrue(responseCodeOk("""{"error":[]}""", null, null, errorArrayField = "error"))
        assertFalse(responseCodeOk("not json", null, null))
    }
}

class ParsedAmountTest {
    @Test
    fun `a magnitude is the whole of an amount whatever its sign`() {
        val minor = ParsedAmount(minorUnits = 250L, decimalMajor = null, sign = 1)
        assertTrue(minor.isMagnitudeOf(ParsedAmount(-250L, null, -1)))
        assertFalse(minor.isMagnitudeOf(ParsedAmount(300L, null, 1)))

        val decimal = ParsedAmount(null, BigDecimal("2.50"), 1)
        assertTrue(decimal.isMagnitudeOf(ParsedAmount(null, BigDecimal("-2.5"), -1)))
        assertFalse(decimal.isMagnitudeOf(ParsedAmount(250L, null, 1)))
    }
}

class CounterpartyIdentityTest {
    private val mappings = ApiPeopleMappings()

    private fun identity(
        json: String,
        ignoreUserId: Boolean = false,
    ) = Json
        .parseToJsonElement(json)
        .jsonObject
        .resolveCounterpartyIdentity(counterpartyIdField = "counterparty.id", peopleMappings = mappings, ignoreUserId = ignoreUserId)

    @Test
    fun `a service user number identifies a counterparty first`() {
        assertEquals("service_user:123", identity("""{"counterparty":{"service_user_number":"123","user_id":"u1"}}"""))
    }

    @Test
    fun `a user id identifies a counterparty unless it is throwaway`() {
        val json = """{"counterparty":{"user_id":"u1"}}"""

        assertEquals("user:u1", identity(json))
        assertNull(identity(json, ignoreUserId = true))
    }

    @Test
    fun `a counterparty with nothing to identify it has no identity`() {
        assertNull(identity("""{"counterparty":{"name":"Shop"}}"""))
        assertNull(identity("""{"other":1}"""))
    }
}

class ResponseItemsTest {
    @Test
    fun `a blank array key takes a bare array or object and rejects a scalar`() {
        assertEquals(2, responseItemsArray("""[1,2]""", "")?.size)
        assertEquals(1, responseItemsArray("""{"a":1}""", "")?.size)
        assertNull(responseItemsArray("5", ""))
    }

    @Test
    fun `the parent id falls back to the nearest ancestor item`() {
        val context = ImportUrlContext(ancestorItems = listOf(Json.parseToJsonElement("""{"id":"p1"}""").jsonObject))

        assertEquals("p1", context.resolve("parent.id"))
    }
}

class ConfigValidationTest {
    private val endpoint = ApiEndpointConfig(path = "/people", responseArrayKey = "people")

    @Test
    fun `owning every account and deriving owners from ancestors are exclusive`() {
        val both =
            ApiPersonImportConfig(endpoint, firstNameField = "first", accountOwnerAncestorExpr = "ancestor[0].id", ownsAllAccounts = true)

        assertFailsWith<IllegalArgumentException> { validatePeopleOwnershipConfig(both) }
        validatePeopleOwnershipConfig(both.copy(ownsAllAccounts = false))
        validatePeopleOwnershipConfig(both.copy(accountOwnerAncestorExpr = null))
    }

    @Test
    fun `an identifiers body that is not a json object reads as none`() {
        assertNull(parseJsonObjectOrNull("not json"))
        assertNull(parseJsonObjectOrNull("[1]"))
        assertEquals(1, parseJsonObjectOrNull("""{"a":1}""")?.size)
    }
}
