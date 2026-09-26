package com.moneymanager.rest

import com.moneymanager.domain.model.apistrategy.ApiRequestSigningConfig
import com.moneymanager.domain.model.apistrategy.BodyFormat
import com.moneymanager.domain.model.apistrategy.FieldPlacement
import com.moneymanager.domain.model.apistrategy.JwtAlgorithm
import com.moneymanager.domain.model.apistrategy.JwtField
import com.moneymanager.domain.model.apistrategy.JwtSigningConfig
import com.moneymanager.domain.model.apistrategy.NonceFormat
import com.moneymanager.domain.model.apistrategy.ParamStringFormat
import com.moneymanager.domain.model.apistrategy.RequestIdFormat
import com.moneymanager.domain.model.apistrategy.SecretEncoding
import com.moneymanager.domain.model.apistrategy.SigFieldLocation
import com.moneymanager.domain.model.apistrategy.SigPart
import com.moneymanager.domain.model.apistrategy.SignatureEncoding
import com.moneymanager.domain.model.apistrategy.SigningAlgorithm
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.SHA512
import dev.whyoleg.cryptography.random.CryptographyRandom
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
 * (optional) request id / method / signature into the right header, query param or body field; or,
 * when [ApiRequestSigningConfig.jwt] is set, attaching a freshly signed JWT instead.
 *
 * One implementation covers Crypto.com, Binance, Kraken and Coinbase with no per-provider branches —
 * the exchanges differ only in their config values (see [ApiRequestSigningConfig] KDoc).
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
     * @param httpMethod the HTTP verb, for a JWT's `{method}` template token
     * @param nowEpochSeconds the signing instant, for a JWT's `{now}`/`{exp}` template tokens
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
        httpMethod: String = "GET",
        nowEpochSeconds: Long = nonce / 1_000,
    ): SignedRequest {
        config.jwt?.let { jwt ->
            return signWithJwt(jwt, endpointUrl, path, params, apiKey, apiSecret, httpMethod, nowEpochSeconds)
        }
        val apiKeyPlacement = requireNotNull(config.apiKey) { "HMAC request signing needs an api key placement" }
        val nonceSpec = requireNotNull(config.nonce) { "HMAC request signing needs a nonce" }
        val signaturePlacement = requireNotNull(config.signature) { "HMAC request signing needs a signature placement" }
        val nonceValue = formatNonce(nonce, nonceSpec.format)
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
        place(apiKeyPlacement, apiKey, headers, queryParams, bodyFields)
        place(nonceSpec.placement, nonceValue, headers, queryParams, bodyFields)
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
        place(signaturePlacement, signature, headers, queryParams, bodyFields)

        val finalQuery = encodeQuery(queryParams)
        val finalUrl = if (finalQuery.isEmpty()) endpointUrl else "$endpointUrl?$finalQuery"
        val (finalBody, contentType) = finalBody(params, bodyFields)
        return SignedRequest(url = finalUrl, headers = headers, body = finalBody, contentType = contentType)
    }

    /**
     * Signs [JwtSigningConfig.header]/[JwtSigningConfig.claims] rendered for this request and places the
     * token. Request params go in the query string (a JWT covers only what its claims name, so there is
     * no body to sign).
     */
    private suspend fun signWithJwt(
        jwt: JwtSigningConfig,
        endpointUrl: String,
        path: String,
        params: Map<String, String>,
        apiKey: String,
        apiSecret: String,
        httpMethod: String,
        nowEpochSeconds: Long,
    ): SignedRequest {
        val key = decodeJwtKey(apiSecret, jwt.algorithm)
        val tokens =
            mapOf(
                "{alg}" to key.algName,
                "{apiKey}" to apiKey,
                "{nonceHex}" to CryptographyRandom.Default.nextBytes(JWT_NONCE_BYTES).toHex(),
                "{now}" to nowEpochSeconds.toString(),
                "{exp}" to (nowEpochSeconds + jwt.ttlSeconds).toString(),
                "{method}" to httpMethod,
                "{host}" to hostOf(endpointUrl),
                "{path}" to path.substringBefore('?'),
            )
        val signingInput = base64Url(jwtJson(jwt.header, tokens)) + "." + base64Url(jwtJson(jwt.claims, tokens))
        val signature = key.sign(signingInput.encodeToByteArray())
        val token = jwt.prefix + signingInput + "." + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(signature)

        val headers = mutableMapOf<String, String>()
        val queryParams = LinkedHashMap(params)
        val bodyFields = linkedMapOf<String, String>()
        place(jwt.placement, token, headers, queryParams, bodyFields)
        val finalQuery = encodeQuery(queryParams)
        val finalUrl = if (finalQuery.isEmpty()) endpointUrl else "$endpointUrl?$finalQuery"
        return SignedRequest(url = finalUrl, headers = headers, body = null, contentType = null)
    }

    private fun jwtJson(
        fields: List<JwtField>,
        tokens: Map<String, String>,
    ): String =
        fields.joinToString(",", "{", "}") { field ->
            val value = tokens.entries.fold(field.template) { acc, (token, v) -> acc.replace(token, v) }
            val rendered =
                if (field.numeric) {
                    // Re-render the parsed Long: toLongOrNull accepts forms like "+5" that are not valid JSON numbers.
                    requireNotNull(value.toLongOrNull()) { "JWT field '${field.name}' is numeric but rendered as '$value'" }.toString()
                } else {
                    jsonString(value)
                }
            "${jsonString(field.name)}:$rendered"
        }

    private fun place(
        placement: FieldPlacement,
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

    private fun formatNonce(
        nonce: Long,
        format: NonceFormat,
    ): String =
        when (format) {
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

private const val JWT_NONCE_BYTES = 16

private const val PEM_LINE_LENGTH = 64

private fun base64Url(json: String): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(json.encodeToByteArray())

private fun hostOf(url: String): String = url.substringAfter("://", url).substringBefore('/')

private val PEM_BLOCK = Regex("-----BEGIN ([A-Z ]+)-----(.*?)-----END \\1-----", RegexOption.DOT_MATCHES_ALL)

/**
 * Restores a PEM block's line structure. A key pasted into a single-line field, or copied out of a JSON
 * key file, arrives with its newlines collapsed or as literal `\n` escapes, and a PEM parser rejects
 * both. The body is re-wrapped from its bare base64, so any of those forms decodes the same.
 */
internal fun normalizePem(pem: String): String {
    val unescaped = pem.replace("\\n", "\n").trim()
    val match = PEM_BLOCK.find(unescaped) ?: return unescaped
    val label = match.groupValues[1]
    val body = match.groupValues[2].filterNot { it.isWhitespace() }
    return buildString {
        append("-----BEGIN ").append(label).append("-----\n")
        body.chunked(PEM_LINE_LENGTH).forEach { append(it).append('\n') }
        append("-----END ").append(label).append("-----\n")
    }
}

/** A PEM label and the key format its body is in. */
private val PEM_LABEL_FORMATS =
    mapOf(
        "EC PRIVATE KEY" to EC.PrivateKey.Format.PEM.SEC1,
        "PRIVATE KEY" to EC.PrivateKey.Format.PEM.Generic,
    )

/** The byte length of an Ed25519 secret as providers hand it out (seed + public key). */
private const val ED25519_SECRET_BYTES = 64

/** The byte length of an Ed25519 seed — the private key proper. */
private const val ED25519_SEED_BYTES = 32

/** A decoded JWT signing key: the JWS `alg` it signs as, and the signing operation. */
private class JwtKey(
    val algName: String,
    val sign: suspend (ByteArray) -> ByteArray,
)

private suspend fun decodeJwtKey(
    secret: String,
    algorithm: JwtAlgorithm,
): JwtKey =
    when (algorithm) {
        JwtAlgorithm.ES256 -> es256Key(decodeP256PrivateKey(secret))
        JwtAlgorithm.EdDSA -> edDsaKey(decodeEd25519PrivateKey(secret))
        // Try Ed25519 first only when the secret has an Ed25519 secret's shape, so a malformed EC key
        // still reports why it is not a valid EC key.
        JwtAlgorithm.DETECT ->
            if (looksLikeEd25519Secret(secret)) {
                edDsaKey(decodeEd25519PrivateKey(secret))
            } else {
                runCatching { es256Key(decodeP256PrivateKey(secret)) }
                    .recoverCatching { ecError -> runCatching { edDsaKey(decodeEd25519PrivateKey(secret)) }.getOrElse { throw ecError } }
                    .getOrThrow()
            }
    }

private fun es256Key(key: ECDSA.PrivateKey): JwtKey {
    val generator = key.signatureGenerator(SHA256, ECDSA.SignatureFormat.RAW)
    return JwtKey("ES256") { generator.generateSignature(it) }
}

private fun edDsaKey(key: EdDSA.PrivateKey): JwtKey {
    val generator = key.signatureGenerator()
    return JwtKey("EdDSA") { generator.generateSignature(it) }
}

private fun base64SecretBytes(secret: String): ByteArray? =
    runCatching { Base64.decode(secret.replace("\\n", "").filterNot { it.isWhitespace() }) }.getOrNull()

private fun looksLikeEd25519Secret(secret: String): Boolean =
    !secret.contains("-----BEGIN") && base64SecretBytes(secret)?.size == ED25519_SECRET_BYTES

/**
 * Decodes an Ed25519 private key: a PKCS#8 `PRIVATE KEY` PEM block, or the base64 of a 32-byte seed or
 * of a 64-byte seed + public key (how Coinbase CDP hands out its Ed25519 secrets).
 */
private suspend fun decodeEd25519PrivateKey(secret: String): EdDSA.PrivateKey {
    val decoder = CryptographyProvider.Default.get(EdDSA).privateKeyDecoder(EdDSA.Curve.Ed25519)
    val pem = normalizePem(secret)
    if (PEM_BLOCK.find(pem) != null) return decoder.decodeFromByteArray(EdDSA.PrivateKey.Format.PEM, pem.encodeToByteArray())
    val bytes = requireNotNull(base64SecretBytes(secret)) { "The API secret is neither a PEM private key nor base64" }
    return when (bytes.size) {
        ED25519_SECRET_BYTES, ED25519_SEED_BYTES ->
            decoder.decodeFromByteArray(
                EdDSA.PrivateKey.Format.RAW,
                bytes.copyOf(ED25519_SEED_BYTES),
            )
        else -> decoder.decodeFromByteArray(EdDSA.PrivateKey.Format.DER, bytes)
    }
}

/**
 * Decodes a P-256 private key from however a provider hands it out or a user pastes it: a SEC1
 * (`EC PRIVATE KEY`) or PKCS#8 (`PRIVATE KEY`) PEM block — also when embedded in a downloaded JSON key
 * file or stripped of its newlines — or the bare base64 of the same DER, or of a raw 32-byte key. Never
 * echoes the secret in an error.
 */
private suspend fun decodeP256PrivateKey(secret: String): ECDSA.PrivateKey {
    val decoder = CryptographyProvider.Default.get(ECDSA).privateKeyDecoder(EC.Curve.P256)
    val pem = normalizePem(secret)
    PEM_BLOCK.find(pem)?.let { block ->
        val format =
            requireNotNull(PEM_LABEL_FORMATS[block.groupValues[1]]) {
                "The API secret is a '${block.groupValues[1]}' PEM block, not an EC private key"
            }
        return decoder.decodeFromByteArray(format, pem.encodeToByteArray())
    }
    val bytes =
        requireNotNull(runCatching { Base64.decode(pem.filterNot { it.isWhitespace() }) }.getOrNull()) {
            "The API secret is neither a PEM private key nor base64 - paste the whole private key"
        }
    require(bytes.size != ED25519_SECRET_BYTES) {
        "The API secret looks like an Ed25519 key; this provider needs an ECDSA (ES256) key - recreate it as ECDSA"
    }
    val formats = listOf(EC.PrivateKey.Format.DER.SEC1, EC.PrivateKey.Format.DER.Generic, EC.PrivateKey.Format.RAW)
    return formats.firstNotNullOfOrNull { format -> runCatching { decoder.decodeFromByteArray(format, bytes) }.getOrNull() }
        ?: throw IllegalArgumentException("The API secret is not a P-256 (ECDSA) private key")
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
