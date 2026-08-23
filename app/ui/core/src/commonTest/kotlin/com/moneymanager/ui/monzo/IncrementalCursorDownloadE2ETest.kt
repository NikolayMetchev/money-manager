package com.moneymanager.ui.monzo

import com.moneymanager.apiimporter.downloadApiSessionAccounts
import com.moneymanager.apiimporter.downloadApiSessionTransactions
import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.importengineapi.createApiCredential
import com.moneymanager.importengineapi.createApiSession
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
import kotlin.time.Instant

private const val CURSOR_ACCOUNT_ID = "acc_00009TEST000000000042"

private val CURSOR_ACCOUNTS_JSON =
    """
    {
      "accounts": [
        {
          "id": "$CURSOR_ACCOUNT_ID",
          "closed": false,
          "created": "2022-01-01T00:00:00.000Z",
          "description": "user_00009TEST000000user",
          "type": "uk_retail",
          "currency": "GBP"
        }
      ]
    }
    """.trimIndent()

private fun transactionsPage(
    id: String,
    created: String,
) = """
    {
      "transactions": [
        {
          "id": "$id",
          "account_id": "$CURSOR_ACCOUNT_ID",
          "created": "$created",
          "amount": -1250,
          "currency": "GBP",
          "description": "COFFEE SHOP LTD LONDON GBR",
          "merchant": { "name": "Coffee Shop Ltd" },
          "counterparty": {}
        }
      ]
    }
    """.trimIndent()

private const val EMPTY_PAGE_JSON = """{ "transactions": [] }"""

/**
 * The cursor-paging counterpart of [com.moneymanager.ui.wise.IncrementalApiDownloadE2ETest]: there are
 * no date windows to clamp, so incrementality has to come from ending the backwards walk as soon as a
 * page falls below the watermark instead of paging all the way to the account's opening.
 */
class IncrementalCursorDownloadE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val token = "test-monzo-token"

    // A feed that pages backwards: newest page, then an older one, then exhaustion. Every timestamp is
    // comfortably in the past, so a watermark recorded "now" puts the whole feed below the cutoff.
    private fun engine() =
        MockEngine { request ->
            val url = request.url.toString()
            val json =
                when {
                    url.contains("/accounts") -> CURSOR_ACCOUNTS_JSON
                    !url.contains("before=") -> transactionsPage("tx_page_1", "2024-06-03T09:15:00.000Z")
                    url.contains("before=2024-06-03") -> transactionsPage("tx_page_2", "2024-05-03T09:15:00.000Z")
                    else -> EMPTY_PAGE_JSON
                }
            respond(content = json, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }

    private suspend fun download(credentialId: ApiCredentialId): Int {
        val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
        val strategy =
            repositories.apiImportStrategyRepository
                .getAllStrategies()
                .first()
                .single { it.name == "Monzo" }
        val sessionId = repositories.importEngine.createApiSession(token, deviceId, now, credentialId)
        val watermarks = repositories.apiSessionRepository.getDownloadWatermarks(credentialId, sessionId)

        fun clientFor() =
            createApiClient(
                trafficRecorder = ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
                engine = engine(),
            )

        downloadApiSessionAccounts(
            token = token,
            apiClient = clientFor(),
            apiSessionRepository = repositories.apiSessionRepository,
            sessionId = sessionId,
            strategy = strategy,
        )
        return downloadApiSessionTransactions(
            token = token,
            apiClient = clientFor(),
            apiSessionRepository = repositories.apiSessionRepository,
            sessionId = sessionId,
            strategy = strategy,
            importEngine = repositories.importEngine,
            watermarks = watermarks,
        ).transactionResponseCount
    }

    @Test
    fun `a second cursor download stops paging once it reaches the watermark`() =
        runTest {
            val credentialId = repositories.importEngine.createApiCredential(token, now)

            // Page 1, page 2, then the empty page that ends the walk.
            assertEquals(3, download(credentialId), "the first download pages back to the account's opening")

            // The first page already sits below the watermark, so the walk ends there.
            assertEquals(1, download(credentialId), "the second download stops at the first page")
        }
}
