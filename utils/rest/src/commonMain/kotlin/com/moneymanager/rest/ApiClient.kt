package com.moneymanager.rest

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.util.AttributeKey
import kotlin.time.Instant

/**
 * Strong Customer Authentication challenge-signing parameters. When a request is rejected with
 * [triggerStatus] and carries a [challengeHeader] one-time token, the token is signed via [sign] and
 * the request is retried once with the challenge echoed back plus the signature in [signatureHeader].
 * This is generic; the header names and status come from the provider's strategy config.
 */
class ScaParams(
    val challengeHeader: String,
    val signatureHeader: String,
    val triggerStatus: Int,
    val sign: (oneTimeToken: String) -> String,
)

class ApiClient(
    private val httpClient: HttpClient,
) {
    suspend fun get(
        url: String,
        bearerToken: String,
        sca: ScaParams? = null,
        endpointKey: String? = null,
        coversUntil: Instant? = null,
    ): ApiHttpResponse {
        val response =
            httpClient.get(url) {
                bearerAuth(bearerToken)
                recordProvenance(endpointKey, coversUntil)
            }
        if (sca != null && response.status.value == sca.triggerStatus) {
            val oneTimeToken = response.headers[sca.challengeHeader]
            if (!oneTimeToken.isNullOrBlank()) {
                val signature = sca.sign(oneTimeToken)
                val signed =
                    httpClient.get(url) {
                        bearerAuth(bearerToken)
                        recordProvenance(endpointKey, coversUntil)
                        header(sca.challengeHeader, oneTimeToken)
                        header(sca.signatureHeader, signature)
                    }
                return signed.toApiHttpResponse()
            }
        }
        return response.toApiHttpResponse()
    }

    /**
     * Issues a request with an explicit [method], [headers] and optional [body] — the generic path used
     * by signed exchange APIs (the signature/api-key/nonce are already baked into [headers]/[body]/[url]
     * by [ApiRequestSigner]). The traffic interceptor records only method/url/(redacted) headers, never
     * the body, so a secret carried in the JSON body (Crypto.com) is never persisted.
     *
     * [recordUrl] is persisted by the traffic recorder in place of the wire [url] when set. Exchanges
     * that sign the request path (Kraken) reject any unsigned query marker appended to the real URL, so
     * bookkeeping markers must travel out-of-band of the wire request.
     */
    suspend fun send(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String? = null,
        recordUrl: String? = null,
        endpointKey: String? = null,
        coversUntil: Instant? = null,
    ): ApiHttpResponse {
        val response =
            httpClient.request(url) {
                this.method = HttpMethod.parse(method)
                recordUrl?.let { attributes.put(apiRecordUrlKey, it) }
                recordProvenance(endpointKey, coversUntil)
                headers.forEach { (name, value) -> header(name, value) }
                if (body != null) {
                    contentType?.let { header(HttpHeaders.ContentType, it) }
                    setBody(body)
                }
            }
        return response.toApiHttpResponse()
    }

    private suspend fun HttpResponse.toApiHttpResponse(): ApiHttpResponse =
        ApiHttpResponse(
            statusCode = status.value,
            body = call.attributes.getOrNull(apiResponseBodyKey) ?: bodyAsText(),
            responseId = call.attributes[apiResponseIdKey],
            requestId = call.attributes[apiRequestIdKey],
        )
}

data class ApiHttpResponse(
    val statusCode: Int,
    val body: String,
    val responseId: Long,
    val requestId: Long,
)

interface ApiTrafficRecorder {
    suspend fun recordRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        endpointKey: String?,
        coversUntil: Instant?,
    ): Long

    suspend fun recordResponse(
        requestId: Long,
        body: String,
    ): Long
}

fun createApiClient(
    trafficRecorder: ApiTrafficRecorder,
    engine: HttpClientEngine?,
): ApiClient {
    val httpClient = if (engine != null) HttpClient(engine) else HttpClient()

    httpClient.plugin(HttpSend).intercept { request ->
        val requestId =
            trafficRecorder.recordRequest(
                method = request.method.value,
                url = request.attributes.getOrNull(apiRecordUrlKey) ?: request.url.buildString(),
                headers =
                    request.headers
                        .entries()
                        .associate { (key, values) -> key to values.joinToString(",") }
                        .filterKeys { !isSensitiveHeader(it) },
                endpointKey = request.attributes.getOrNull(apiEndpointKeyKey),
                coversUntil = request.attributes.getOrNull(apiCoversUntilKey)?.let(Instant::fromEpochMilliseconds),
            )

        val call = execute(request)
        val responseBody = call.response.bodyAsText()
        // Only persist non-blank bodies: the api_response.json column rejects empty values, and an
        // empty body (e.g. an error or no-content response) carries nothing importable. The caller
        // still sees the status code and can surface a meaningful error.
        val responseId = if (responseBody.isNotBlank()) trafficRecorder.recordResponse(requestId, responseBody) else NO_RESPONSE_ID
        call.attributes.put(apiResponseBodyKey, responseBody)
        call.attributes.put(apiResponseIdKey, responseId)
        call.attributes.put(apiRequestIdKey, requestId)

        call
    }

    return ApiClient(httpClient)
}

/** Sentinel response id used when an empty body is not persisted (see the traffic interceptor). */
const val NO_RESPONSE_ID: Long = -1L

/**
 * Headers that may carry secrets and must never be persisted to the recorded request log:
 * the bearer token plus any one-time SCA challenge/signature headers (e.g. Wise's
 * `x-2fa-approval` / `X-Signature`). Matched by substring so provider-specific header names
 * configured in strategies are still covered without coupling this layer to that config.
 */
private fun isSensitiveHeader(key: String): Boolean {
    if (key.equals(HttpHeaders.Authorization, ignoreCase = true)) return true
    val lower = key.lowercase()
    return lower.contains("signature") ||
        lower.contains("2fa") ||
        lower.contains("approval") ||
        // Exchange auth headers: Binance `X-MBX-APIKEY`, Kraken `API-Key` / `API-Sign`.
        lower.contains("api-key") ||
        lower.contains("apikey") ||
        lower.contains("api-sign")
}

/**
 * Tags a request with the download provenance the traffic recorder persists alongside it: which logical
 * endpoint it served and how far its data reaches. A later download of the same credential reads these
 * back as its incremental watermark, so it can resume instead of re-sweeping the whole lookback.
 */
private fun HttpRequestBuilder.recordProvenance(
    endpointKey: String?,
    coversUntil: Instant?,
) {
    endpointKey?.let { attributes.put(apiEndpointKeyKey, it) }
    coversUntil?.let { attributes.put(apiCoversUntilKey, it.toEpochMilliseconds()) }
}

private val apiRecordUrlKey = AttributeKey<String>("ApiRecordUrl")
private val apiEndpointKeyKey = AttributeKey<String>("ApiEndpointKey")
private val apiCoversUntilKey = AttributeKey<Long>("ApiCoversUntil")
private val apiResponseBodyKey = AttributeKey<String>("ApiResponseBody")
private val apiResponseIdKey = AttributeKey<Long>("ApiResponseId")
private val apiRequestIdKey = AttributeKey<Long>("ApiRequestId")
