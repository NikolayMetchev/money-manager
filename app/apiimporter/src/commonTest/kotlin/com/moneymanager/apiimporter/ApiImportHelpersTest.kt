package com.moneymanager.apiimporter

import kotlin.test.Test
import kotlin.test.assertEquals
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
