package com.moneymanager.ui.paypal

import com.moneymanager.apiimporter.ApiDownloadCredentials
import com.moneymanager.apiimporter.ApiDownloadNotPossibleException
import com.moneymanager.apiimporter.downloadApiSession
import com.moneymanager.apiimporter.importApiSessionTransactions
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.rest.ApiSessionTrafficRecorder
import com.moneymanager.rest.createApiClient
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.hasDisplayValue
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.toDuration

private const val CLIENT_ID = "paypal-client-id"
private const val CLIENT_SECRET = "paypal-client-secret"
private const val ACCESS_TOKEN = "A21AA-exchanged-token"

private const val BALANCES_JSON =
    """
{
  "balances": [
    { "currency": "GBP", "primary": true, "total_balance": { "currency_code": "GBP", "value": "40.41" } },
    { "currency": "USD", "total_balance": { "currency_code": "USD", "value": "12.50" } }
  ],
  "account_id": "MERCHANT123",
  "as_of_time": "2026-10-01T00:00:00Z"
}
"""

/** One Transaction Search item, as PayPal formats it (offset without a colon, signed decimal amounts). */
private data class PayPalItem(
    val id: String,
    val eventCode: String,
    val at: Instant,
    val currency: String,
    val amount: String,
    val fee: String? = null,
    val status: String = "S",
    val payerName: String? = null,
    val subject: String? = null,
) {
    fun json(): String {
        val timestamp = at.toString().replace("Z", "+0000")
        val feeJson = fee?.let { """, "fee_amount": { "currency_code": "$currency", "value": "$it" }""" }.orEmpty()
        val subjectJson = subject?.let { """, "transaction_subject": "$it"""" }.orEmpty()
        val payerJson =
            payerName
                ?.let { """, "payer_info": { "email_address": "payer@example.com", "payer_name": { "alternate_full_name": "$it" } }""" }
                .orEmpty()
        return """
            {
              "transaction_info": {
                "transaction_id": "$id",
                "transaction_event_code": "$eventCode",
                "transaction_initiation_date": "$timestamp",
                "transaction_amount": { "currency_code": "$currency", "value": "$amount" },
                "transaction_status": "$status"$feeJson$subjectJson
              }$payerJson
            }
        """
    }
}

class PayPalImportE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    // Whole seconds, like PayPal's timestamps.
    private val recent = Instant.fromEpochSeconds((Clock.System.now() - 10.days).epochSeconds)

    private val items =
        listOf(
            // A payment received: gross 50.00 with a 1.59 fee on top.
            PayPalItem("PAY1", "T0006", recent, "GBP", "50.00", fee = "-1.59", payerName = "Alice Buyer", subject = "Invoice 7"),
            // A payment sent.
            PayPalItem("PAY2", "T0006", recent + 1.days, "GBP", "-8.00", payerName = "Coffee Roasters"),
            // A currency conversion: GBP out, USD in.
            PayPalItem("CNV1", "T0200", recent + 2.days, "GBP", "-10.00"),
            PayPalItem("CNV2", "T0200", recent + 2.days, "USD", "12.50"),
            // Denied: kept for the record, but excluded from balances.
            PayPalItem("DEN1", "T0006", recent + 3.days, "GBP", "-99.00", status = "D", payerName = "Coffee Roasters"),
        )

    private class Captured {
        val tokenRequests = mutableListOf<HttpRequestData>()
        val bearerHeaders = mutableListOf<String?>()
        val transactionUrls = mutableListOf<String>()
    }

    private fun mockEngine(
        captured: Captured,
        tokenStatus: HttpStatusCode = HttpStatusCode.OK,
    ) = MockEngine { request ->
        val url = request.url
        val json =
            when {
                url.encodedPath == "/v1/oauth2/token" -> {
                    captured.tokenRequests += request
                    if (tokenStatus != HttpStatusCode.OK) {
                        return@MockEngine respond(
                            content = """{ "error": "invalid_client", "error_description": "Client Authentication failed" }""",
                            status = tokenStatus,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                    """{ "scope": "https://uri.paypal.com/services/reporting/search/read", "access_token": "$ACCESS_TOKEN", "token_type": "Bearer", "expires_in": 32400 }"""
                }
                url.encodedPath == "/v1/reporting/balances" -> {
                    captured.bearerHeaders += request.headers[HttpHeaders.Authorization]
                    BALANCES_JSON
                }
                url.encodedPath == "/v1/reporting/transactions" -> {
                    captured.bearerHeaders += request.headers[HttpHeaders.Authorization]
                    captured.transactionUrls += url.toString()
                    val start = Instant.parse(url.parameters["start_date"]!!)
                    val end = Instant.parse(url.parameters["end_date"]!!)
                    val currency = url.parameters["transaction_currency"]
                    val matching = items.filter { it.currency == currency && it.at >= start && it.at <= end }
                    """
                    {
                      "transaction_details": [${matching.joinToString(",") { it.json() }}],
                      "account_number": "MERCHANT123",
                      "page": 1,
                      "total_items": ${matching.size},
                      "total_pages": 1
                    }
                    """
                }
                else -> error("Unexpected request: $url")
            }
        respond(content = json, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private suspend fun payPal(): ApiImportStrategy =
        repositories.apiImportStrategyRepository
            .getAllStrategies()
            .first()
            .single { it.name == "PayPal API" }

    @Test
    fun `paypal exchanges its client credentials for a bearer token and imports per-currency accounts`() =
        runTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val sessionId = repositories.apiSessionRepository.createSession(deviceId, Clock.System.now(), null)
            val strategy = payPal()
            val captured = Captured()
            val apiClient =
                createApiClient(
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
                    engine = mockEngine(captured),
                )

            downloadApiSession(
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
                importEngine = repositories.importEngine,
                credentials = ApiDownloadCredentials(token = CLIENT_ID, apiSecret = CLIENT_SECRET),
            )

            // One token per download, via HTTP Basic client authentication and a form-encoded grant.
            val tokenRequest = captured.tokenRequests.single()
            assertEquals("POST", tokenRequest.method.value)
            assertEquals(
                "Basic " + Base64.encode("$CLIENT_ID:$CLIENT_SECRET".encodeToByteArray()),
                tokenRequest.headers[HttpHeaders.Authorization],
            )
            val body = tokenRequest.body as OutgoingContent.ByteArrayContent
            assertEquals("grant_type=client_credentials", body.bytes().decodeToString())
            assertTrue(captured.bearerHeaders.isNotEmpty())
            assertTrue(captured.bearerHeaders.all { it == "Bearer $ACCESS_TOKEN" }, "every request uses the exchanged token")

            // The token response carries the token itself, so it is never recorded.
            val recordedUrls = repositories.apiSessionRepository.getRequestsBySession(sessionId).map { it.url }
            assertTrue(recordedUrls.none { "oauth2" in it }, "the token exchange must not be recorded: $recordedUrls")

            // 31-day windows, page-number paging, one feed per currency.
            assertTrue(captured.transactionUrls.all { "page=1" in it && "page_size=500" in it && "fields=all" in it })
            assertTrue(captured.transactionUrls.any { "transaction_currency=USD" in it })
            for (url in captured.transactionUrls) {
                val parameters = Url(url).parameters
                val span = Instant.parse(parameters["end_date"]!!) - Instant.parse(parameters["start_date"]!!)
                assertTrue(span < 31.toDuration(DurationUnit.DAYS), "window too long: $url")
            }

            val result =
                importApiSessionTransactions(
                    apiSessionRepository = repositories.apiSessionRepository,
                    currencyRepository = repositories.currencyRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = repositories.importEngine,
                )
            assertEquals(0, result.errorCount, "no import errors expected")

            val accounts = repositories.accountRepository.getAllAccounts().first()
            val gbp = accounts.single { it.name == "PayPal GBP" }
            val usd = accounts.single { it.name == "PayPal USD" }
            val alice = accounts.single { it.name == "Alice Buyer" }
            val coffee = accounts.single { it.name == "Coffee Roasters" }
            val fees = accounts.single { it.name == "PayPal API Fees" }
            val conversion = accounts.single { it.name == "PayPal Currency Conversion" }

            val gbpTransfers = repositories.transactionRepository.getTransactionsByAccount(gbp.id).first()
            val received = gbpTransfers.single { it.amount.hasDisplayValue("50.00") }
            assertEquals(alice.id, received.sourceAccountId)
            assertEquals(gbp.id, received.targetAccountId)
            val fee = gbpTransfers.single { it.amount.hasDisplayValue("1.59") }
            assertEquals(gbp.id, fee.sourceAccountId)
            assertEquals(fees.id, fee.targetAccountId)
            val sent = gbpTransfers.filter { it.amount.hasDisplayValue("8.00") }.single()
            assertEquals(gbp.id, sent.sourceAccountId)
            assertEquals(coffee.id, sent.targetAccountId)
            val conversionOut = gbpTransfers.single { it.amount.hasDisplayValue("10.00") }
            assertEquals(conversion.id, conversionOut.targetAccountId)

            val usdTransfers = repositories.transactionRepository.getTransactionsByAccount(usd.id).first()
            val conversionIn = usdTransfers.single { it.amount.hasDisplayValue("12.50") }
            assertEquals(conversion.id, conversionIn.sourceAccountId)
            assertEquals(usd.id, conversionIn.targetAccountId)
        }

    @Test
    fun `a rejected token exchange stops the download before any data request`() =
        runTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val sessionId = repositories.apiSessionRepository.createSession(deviceId, Clock.System.now(), null)
            val captured = Captured()
            val apiClient =
                createApiClient(
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
                    engine = mockEngine(captured, tokenStatus = HttpStatusCode.Unauthorized),
                )

            val failure =
                assertFailsWith<ApiDownloadNotPossibleException> {
                    downloadApiSession(
                        apiClient = apiClient,
                        apiSessionRepository = repositories.apiSessionRepository,
                        sessionId = sessionId,
                        strategy = payPal(),
                        importEngine = repositories.importEngine,
                        credentials = ApiDownloadCredentials(token = CLIENT_ID, apiSecret = "wrong"),
                    )
                }
            assertTrue("Client Authentication failed" in failure.message, failure.message)
            assertTrue(captured.bearerHeaders.isEmpty(), "no data request after a failed exchange")
        }
}
