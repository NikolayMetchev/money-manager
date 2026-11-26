package com.moneymanager.domain.model.apistrategy

import com.moneymanager.domain.model.serialization.SortedStringToStringMapSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

@Serializable
enum class SigningAlgorithm {
    HMAC_SHA256,
    HMAC_SHA512,

    /**
     * Ed25519, the api secret being the private key: a PKCS#8 PEM (`openssl genpkey -algorithm ed25519`),
     * or the base64 of a 32-byte seed or a 64-byte seed + public key. [SecretEncoding] doesn't apply.
     */
    ED25519,
}

/** How the API secret is decoded before use as the HMAC key (an Ed25519 key is decoded by its own format). */
@Serializable
enum class SecretEncoding {
    /** The secret is used as raw UTF-8 bytes (Crypto.com, Binance). */
    UTF8,

    /** The secret is Base64-decoded to bytes first (Kraken). */
    BASE64,
}

/** How the computed signature is encoded for transmission. */
@Serializable
enum class SignatureEncoding {
    HEX,
    BASE64,
}

/** How a set of request parameters is serialised into part of the signed message. */
@Serializable
enum class ParamStringFormat {
    /** Keys sorted ascending, concatenated with no separators: `k1v1k2v2…` (Crypto.com). */
    SORTED_CONCAT,

    /** `k1=v1&k2=v2` query-string form (Binance). */
    QUERY_STRING,
}

/** Where a signing field (api key, nonce, signature) is placed on the outgoing request. */
@Serializable
enum class SigFieldLocation {
    HEADER,
    QUERY,
    BODY_FIELD,
}

/** How the nonce value is generated. */
@Serializable
enum class NonceFormat {
    EPOCH_MS,
    EPOCH_US,
    EPOCH_NS,

    /** A strictly increasing counter (rarely needed; timestamp forms are preferred). */
    INCREMENTING,
}

/** How the per-request `id` (Crypto.com) is generated. */
@Serializable
enum class RequestIdFormat {
    INCREMENTING,
    EPOCH_MS,
}

/** How request parameters are marshalled into the HTTP request body. */
@Serializable
enum class BodyFormat {
    /** No body; parameters live in the query string (GET, or Binance signed GET/POST). */
    NONE,

    /** A JSON object `{...}` wrapping the signing fields and params (Crypto.com). */
    JSON_ENVELOPE,

    /** `application/x-www-form-urlencoded` body (Kraken). */
    FORM_URLENCODED,

    /** Params go only in the query string, signature appended there (Binance). */
    QUERY_ONLY,
}

@Serializable
data class FieldPlacement(
    val location: SigFieldLocation,
    val name: String,
)

@Serializable
data class NonceSpec(
    val format: NonceFormat = NonceFormat.EPOCH_MS,
    val placement: FieldPlacement,
)

@Serializable
data class RequestIdSpec(
    val format: RequestIdFormat = RequestIdFormat.INCREMENTING,
    val placement: FieldPlacement,
)

/**
 * One ordered fragment of the message that gets HMAC-signed. The signer concatenates the bytes each
 * part produces, in list order, then signs the result. This is expressive enough for all three target
 * exchanges (see [ApiRequestSigningConfig]).
 */
@Serializable
sealed interface SigPart {
    /** A fixed literal string. */
    @Serializable
    data class Literal(
        val text: String,
    ) : SigPart

    /** The API method name (Crypto.com: the endpoint path minus base, e.g. "private/get-trades"). */
    @Serializable
    data object Method : SigPart

    /** The per-request id (Crypto.com `id`). */
    @Serializable
    data object RequestId : SigPart

    /** The API key. */
    @Serializable
    data object ApiKey : SigPart

    /** The nonce value. */
    @Serializable
    data object Nonce : SigPart

    /** The request URI path (Kraken). */
    @Serializable
    data object Path : SigPart

    /** The request query string without the leading `?` (Binance). */
    @Serializable
    data object QueryString : SigPart

    /** The raw request body (Binance body, Kraken form-encoded post data). */
    @Serializable
    data object Body : SigPart

    /** The request parameters serialised via [format] (Crypto.com sorted-concat). */
    @Serializable
    data class ParamString(
        val format: ParamStringFormat = ParamStringFormat.SORTED_CONCAT,
    ) : SigPart

    /**
     * SHA-256 of the concatenation of [parts], emitted as raw bytes (Kraken's nested hash). Order is
     * semantic (bytes are concatenated in list order) - keeps default insertion-order serialization.
     */
    @Serializable
    data class Sha256(
        val parts: List<SigPart>,
    ) : SigPart
}

/** The message parts are concatenated in order. JWT authentication uses its own header and claims. */
@Serializable
data class ApiRequestSigningConfig(
    val algorithm: SigningAlgorithm = SigningAlgorithm.HMAC_SHA256,
    val secretEncoding: SecretEncoding = SecretEncoding.UTF8,
    val signatureEncoding: SignatureEncoding = SignatureEncoding.HEX,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val message: List<SigPart> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val apiKey: FieldPlacement? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val nonce: NonceSpec? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val signature: FieldPlacement? = null,
    val requestId: RequestIdSpec? = null,
    /** Where the API method name is written on the request (Crypto.com body field "method"); null omits it. */
    val method: FieldPlacement? = null,
    val bodyFormat: BodyFormat = BodyFormat.NONE,
    /** For [BodyFormat.JSON_ENVELOPE], the key under which request params are nested (Crypto.com "params"). */
    val paramsEnvelopeKey: String? = null,
    /** Parameters included in every signed request, such as Binance’s nonce tolerance (recvWindow). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = SortedQueryParamListSerializer::class)
    val signedParams: List<ApiQueryParam> = emptyList(),
    /** Use the provider’s clock for the nonce when local clock skew could invalidate the signature. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val serverTimeSync: ApiServerTimeSync? = null,
    /** Fixed headers for signed requests. Include a literal in the message if the provider signs the header value. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = SortedStringToStringMapSerializer::class)
    val staticHeaders: Map<String, String> = emptyMap(),
    /** JWT authentication supplies its own signing algorithm and message. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val jwt: JwtSigningConfig? = null,
)

/**
 * Locates a provider's current server time, so signed requests can be stamped with the provider's clock
 * rather than the local one (see [ApiRequestSigningConfig.serverTimeSync]).
 *
 * @property path Unsigned endpoint path relative to the strategy's base URL (Binance `api/v3/time`).
 * @property field Dot-path to the epoch-millisecond value in the response (Binance `serverTime`).
 */
@Serializable
data class ApiServerTimeSync(
    val path: String,
    val field: String,
)
