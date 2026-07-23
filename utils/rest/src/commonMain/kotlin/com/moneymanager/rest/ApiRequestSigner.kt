package com.moneymanager.rest

import com.moneymanager.domain.model.apistrategy.ApiRequestSigningConfig
import com.moneymanager.domain.model.apistrategy.BodyFormat
import com.moneymanager.domain.model.apistrategy.NonceFormat
import com.moneymanager.domain.model.apistrategy.ParamStringFormat
import com.moneymanager.domain.model.apistrategy.RequestIdFormat
import com.moneymanager.domain.model.apistrategy.SecretEncoding
import com.moneymanager.domain.model.apistrategy.SigFieldLocation
import com.moneymanager.domain.model.apistrategy.SigPart
import com.moneymanager.domain.model.apistrategy.SignatureEncoding
import com.moneymanager.domain.model.apistrategy.SigningAlgorithm
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.SHA512
import kotlin.io.encoding.Base64

/**
 * A fully assembled, signed HTTP request ready to send: the final [url] (with any query params),
 * [headers] to set, and an optional request [body] with its [contentType].
 */
data class SignedRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
    val contentType: String?,
)

/**
 * The provider-agnostic per-request signer. Interprets an [ApiRequestSigningConfig] to produce a
 * [SignedRequest] — computing an HMAC over a configurable message and placing the api key / nonce /
 * (optional) request id / method / signature into the right header, query param or body field.
 *
 * One implementation covers Crypto.com, Binance and Kraken with no per-provider branches — the three
 * exchanges differ only in their config values (see [ApiRequestSigningConfig] KDoc).
 */
class ApiRequestSigner(
    private val config: ApiRequestSigningConfig,
) {
    /**
     * @param endpointUrl the full endpoint URL without a query string (e.g. `https://api.crypto.com/exchange/v1/private/get-trades`)
     * @param path the URI path portion used by the [SigPart.Path] fragment (e.g. `/exchange/v1/private/get-trades`)
     * @param methodName the API method identifier ([SigPart.Method] and the optional method field; e.g. `private/get-trades`)
     * @param params the request parameters (values already stringified; numbers as strings per exchange rules)
     * @param apiKey the public api key
     * @param apiSecret the api secret (decoded per [ApiRequestSigningConfig.secretEncoding])
     * @param nonce a monotonically increasing value (typically epoch millis)
     * @param requestId the per-request id (Crypto.com `id`); ignored when [ApiRequestSigningConfig.requestId] is null
     */
    suspend fun sign(
        endpointUrl: String,
        path: String,
        methodName: String,
        params: Map<String, String>,
        apiKey: String,
        apiSecret: String,
        nonce: Long,
        requestId: Long,
    ): SignedRequest {
        val nonceValue = formatNonce(nonce)
        val requestIdValue = config.requestId?.let { formatRequestId(it.format, requestId) }

        val headers = mutableMapOf<String, String>()
        val queryParams = mutableMapOf<String, String>()
        val bodyFields = linkedMapOf<String, String>()

        // 1. Params that live in the query go first, so the query reads `<params>&<placed>` (Binance
        //    signs `symbol=…&…&timestamp=…`).
        if (config.bodyFormat == BodyFormat.QUERY_ONLY || config.bodyFormat == BodyFormat.NONE) {
            queryParams.putAll(params)
        }

        // 2. Place method / api key / nonce / request id BEFORE signing (they may be part of the message).
        //    For a form body these land ahead of the params added in step 3, so the body reads
        //    `nonce=…&<params>` (Kraken signs SHA256(nonce + postdata) where postdata starts with nonce).
        config.method?.let { place(it, methodName, headers, queryParams, bodyFields) }
        place(config.apiKey, apiKey, headers, queryParams, bodyFields)
        place(config.nonce.placement, nonceValue, headers, queryParams, bodyFields)
        requestIdValue?.let { place(config.requestId!!.placement, it, headers, queryParams, bodyFields) }

        // 3. Form-body params follow the placed fields. JSON-envelope params are nested at render time.
        if (config.bodyFormat == BodyFormat.FORM_URLENCODED) {
            bodyFields.putAll(params)
        }

        // 3. Compute the signed message and the signature.
        val queryString = encodeQuery(queryParams)
        val bodyString = renderBody(params, bodyFields)
        val messageBytes =
            buildMessageBytes(
                parts = config.message,
                methodName = methodName,
                requestId = requestIdValue.orEmpty(),
                apiKey = apiKey,
                nonce = nonceValue,
                path = path,
                queryString = queryString,
                body = bodyString,
                params = params,
            )
        val signature = encodeSignature(hmac(apiSecret, messageBytes))

        // 4. Place the signature (after signing, so it is not part of the signed message).
        place(config.signature, signature, headers, queryParams, bodyFields)

        val finalQuery = encodeQuery(queryParams)
        val finalUrl = if (finalQuery.isEmpty()) endpointUrl else "$endpointUrl?$finalQuery"
        val (finalBody, contentType) = finalBody(params, bodyFields)
        return SignedRequest(url = finalUrl, headers = headers, body = finalBody, contentType = contentType)
    }

    private fun place(
        placement: com.moneymanager.domain.model.apistrategy.FieldPlacement,
        value: String,
        headers: MutableMap<String, String>,
        queryParams: MutableMap<String, String>,
        bodyFields: MutableMap<String, String>,
    ) {
        when (placement.location) {
            SigFieldLocation.HEADER -> headers[placement.name] = value
            SigFieldLocation.QUERY -> queryParams[placement.name] = value
            SigFieldLocation.BODY_FIELD -> bodyFields[placement.name] = value
        }
    }

    private suspend fun buildMessageBytes(
        parts: List<SigPart>,
        methodName: String,
        requestId: String,
        apiKey: String,
        nonce: String,
        path: String,
        queryString: String,
        body: String,
        params: Map<String, String>,
    ): ByteArray {
        var acc = ByteArray(0)
        for (part in parts) {
            acc +=
                when (part) {
                    is SigPart.Literal -> part.text.encodeToByteArray()
                    SigPart.Method -> methodName.encodeToByteArray()
                    SigPart.RequestId -> requestId.encodeToByteArray()
                    SigPart.ApiKey -> apiKey.encodeToByteArray()
                    SigPart.Nonce -> nonce.encodeToByteArray()
                    SigPart.Path -> path.encodeToByteArray()
                    SigPart.QueryString -> queryString.encodeToByteArray()
                    SigPart.Body -> body.encodeToByteArray()
                    is SigPart.ParamString -> paramString(params, part.format).encodeToByteArray()
                    is SigPart.Sha256 ->
                        sha256(
                            buildMessageBytes(
                                part.parts,
                                methodName,
                                requestId,
                                apiKey,
                                nonce,
                                path,
                                queryString,
                                body,
                                params,
                            ),
                        )
                }
        }
        return acc
    }

    private fun paramString(
        params: Map<String, String>,
        format: ParamStringFormat,
    ): String =
        when (format) {
            ParamStringFormat.SORTED_CONCAT ->
                params.entries.sortedBy { it.key }.joinToString(separator = "") { "${it.key}${it.value}" }
            ParamStringFormat.QUERY_STRING ->
                params.entries.joinToString(separator = "&") { "${it.key}=${it.value}" }
        }

    private fun renderBody(
        params: Map<String, String>,
        bodyFields: Map<String, String>,
    ): String =
        when (config.bodyFormat) {
            BodyFormat.JSON_ENVELOPE -> jsonEnvelope(params, bodyFields)
            BodyFormat.FORM_URLENCODED -> encodeQuery(bodyFields)
            BodyFormat.QUERY_ONLY, BodyFormat.NONE -> ""
        }

    private fun finalBody(
        params: Map<String, String>,
        bodyFields: Map<String, String>,
    ): Pair<String?, String?> =
        when (config.bodyFormat) {
            BodyFormat.JSON_ENVELOPE -> jsonEnvelope(params, bodyFields) to "application/json"
            BodyFormat.FORM_URLENCODED -> encodeQuery(bodyFields) to "application/x-www-form-urlencoded"
            BodyFormat.QUERY_ONLY, BodyFormat.NONE -> null to null
        }

    private fun jsonEnvelope(
        params: Map<String, String>,
        bodyFields: Map<String, String>,
    ): String {
        val entries = mutableListOf<String>()
        for ((k, v) in bodyFields) entries += "${jsonString(k)}:${jsonString(v)}"
        config.paramsEnvelopeKey?.let { key ->
            val paramObj = params.entries.joinToString(",", "{", "}") { "${jsonString(it.key)}:${jsonString(it.value)}" }
            entries += "${jsonString(key)}:$paramObj"
        }
        return entries.joinToString(",", "{", "}")
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                // Per RFC 8259 §7, every other control character (U+0000–U+001F, incl. \b and \f) is escaped as \uXXXX.
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }

    private fun encodeQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { "${percentEncode(it.key)}=${percentEncode(it.value)}" }

    private fun formatNonce(nonce: Long): String =
        when (config.nonce.format) {
            NonceFormat.EPOCH_MS -> nonce.toString()
            NonceFormat.EPOCH_US -> (nonce * 1_000).toString()
            NonceFormat.EPOCH_NS -> (nonce * 1_000_000).toString()
            NonceFormat.INCREMENTING -> nonce.toString()
        }

    private fun formatRequestId(
        format: RequestIdFormat,
        requestId: Long,
    ): String =
        when (format) {
            RequestIdFormat.INCREMENTING, RequestIdFormat.EPOCH_MS -> requestId.toString()
        }

    private suspend fun hmac(
        secret: String,
        message: ByteArray,
    ): ByteArray {
        val provider = CryptographyProvider.Default
        val secretBytes =
            when (config.secretEncoding) {
                SecretEncoding.UTF8 -> secret.encodeToByteArray()
                SecretEncoding.BASE64 -> Base64.decode(secret)
            }
        val digest =
            when (config.algorithm) {
                SigningAlgorithm.HMAC_SHA256 -> SHA256
                SigningAlgorithm.HMAC_SHA512 -> SHA512
            }
        val key = provider.get(HMAC).keyDecoder(digest).decodeFromByteArray(HMAC.Key.Format.RAW, secretBytes)
        return key.signatureGenerator().generateSignature(message)
    }

    private suspend fun sha256(bytes: ByteArray): ByteArray =
        CryptographyProvider.Default
            .get(SHA256)
            .hasher()
            .hash(bytes)

    private fun encodeSignature(bytes: ByteArray): String =
        when (config.signatureEncoding) {
            SignatureEncoding.HEX -> bytes.toHex()
            SignatureEncoding.BASE64 -> Base64.encode(bytes)
        }
}

private fun ByteArray.toHex(): String {
    val hex = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(hex[v ushr 4]).append(hex[v and 0x0F])
    }
    return sb.toString()
}

/**
 * Percent-encodes an application/x-www-form-urlencoded component. Kept in commonMain (no ktor.http
 * here) and used identically when signing and when sending, so the signed and transmitted strings
 * always match. Unreserved characters per RFC 3986 are passed through.
 */
private fun percentEncode(value: String): String {
    val sb = StringBuilder()
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt() and 0xFF
        val ch = c.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
            sb.append(ch)
        } else {
            sb.append('%')
            val hex = "0123456789ABCDEF"
            sb.append(hex[c ushr 4]).append(hex[c and 0x0F])
        }
    }
    return sb.toString()
}
