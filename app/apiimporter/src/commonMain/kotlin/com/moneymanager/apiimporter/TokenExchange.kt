package com.moneymanager.apiimporter

import co.touchlab.kermit.Logger
import com.moneymanager.domain.model.apistrategy.ApiTokenExchange
import com.moneymanager.rest.ApiClient
import io.ktor.http.formUrlEncode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.io.encoding.Base64

private val logger = Logger.withTag("TokenExchange")

/**
 * Trades [clientId] + [clientSecret] for a bearer token per [exchange] (OAuth2 client credentials).
 * The response carries the token itself, so it is never stored as an API response.
 *
 * @throws ApiDownloadNotPossibleException when the provider rejects the credentials or answers without a token.
 */
internal suspend fun exchangeBearerToken(
    apiClient: ApiClient,
    baseUrl: String,
    exchange: ApiTokenExchange,
    clientId: String,
    clientSecret: String,
): String {
    val basic = Base64.encode("$clientId:$clientSecret".encodeToByteArray())
    val url = baseUrl.trimEnd('/') + "/" + exchange.path.trimStart('/')
    // Never the credentials or the token: the request isn't recorded, so this log is its only trace.
    logger.i { "Requesting an access token from $url" }
    val response =
        apiClient.send(
            method = "POST",
            url = url,
            headers = mapOf("Authorization" to "Basic $basic", "Accept" to "application/json"),
            body = exchange.formParams.toList().formUrlEncode(),
            contentType = "application/x-www-form-urlencoded",
            storeResponse = false,
        )
    val json = runCatching { Json.parseToJsonElement(response.body) as? JsonObject }.getOrNull()
    if (response.statusCode != HTTP_OK) {
        // Only the provider's named error fields: an unrecognised body could carry anything, so it stays out
        // of the log and the task message.
        val reason =
            json?.resolveJsonPath("error_description") ?: json?.resolveJsonPath("error")
                ?: "no error detail in the response (${response.body.length} chars)"
        logger.w { "Token request to $url failed with HTTP ${response.statusCode}: $reason" }
        throw ApiDownloadNotPossibleException("Token request failed (HTTP ${response.statusCode}): $reason")
    }
    val token = (json?.resolveJsonPathElement(exchange.accessTokenField) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    if (token == null) {
        logger.w { "Token response from $url has no '${exchange.accessTokenField}' (fields: ${json?.keys.orEmpty()})" }
        throw ApiDownloadNotPossibleException("Token response has no '${exchange.accessTokenField}'.")
    }
    logger.i { "Access token obtained from $url (expires_in=${json.resolveJsonPath("expires_in") ?: "?"}s)" }
    return token
}

private const val HTTP_OK = 200
