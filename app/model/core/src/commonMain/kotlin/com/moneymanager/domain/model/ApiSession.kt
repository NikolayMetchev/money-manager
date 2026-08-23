package com.moneymanager.domain.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

data class ApiCredential(
    val id: ApiCredentialId,
    val token: String,
    val createdAt: Instant,
    val strategyId: ApiImportStrategyId? = null,
    /** PEM-encoded RSA keys for request signing (e.g. Wise SCA); null when not configured. */
    val privateKey: String? = null,
    val publicKey: String? = null,
    /**
     * HMAC secret for signed exchange APIs (ApiAuthType.SIGNED; e.g. Crypto.com, Kraken). For these
     * strategies [token] holds the api key and this holds the api secret. Null for bearer/SCA strategies.
     */
    val apiSecret: String? = null,
) {
    override fun toString(): String =
        "ApiCredential(id=$id, token=<redacted>, createdAt=$createdAt, " +
            "strategyId=$strategyId, privateKey=${if (privateKey != null) "<redacted>" else "null"}, " +
            "publicKey=${if (publicKey != null) "<redacted>" else "null"}, " +
            "apiSecret=${if (apiSecret != null) "<redacted>" else "null"})"
}

@JvmInline
value class ApiCredentialId(
    val id: Long,
) {
    override fun toString() = id.toString()
}

data class ApiSession(
    val id: ApiSessionId,
    val token: String,
    val deviceId: DeviceId,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val credentialId: ApiCredentialId?,
    val importDurationMillis: Long? = null,
)

data class ApiRequest(
    val id: ApiRequestId,
    val sessionId: ApiSessionId,
    val requestedAt: Instant,
    val method: String,
    val url: String,
    val headers: List<ApiRequestHeader>,
)

@Serializable
@JvmInline
value class ApiRequestId(
    val id: Long,
) {
    override fun toString() = id.toString()
}

data class ApiRequestHeader(
    val id: ApiRequestHeaderId,
    val requestId: ApiRequestId,
    val key: String,
    val value: String,
)

@JvmInline
value class ApiRequestHeaderId(
    val id: Long,
) {
    override fun toString() = id.toString()
}

data class ApiResponse(
    val id: ApiResponseId,
    val requestId: ApiRequestId,
    val sessionId: ApiSessionId,
    val respondedAt: Instant,
    val json: String,
)

@JvmInline
value class ApiResponseId(
    val id: Long,
) {
    override fun toString() = id.toString()
}

@Serializable
@JvmInline
value class ApiSessionId(
    val id: Long,
) {
    override fun toString() = id.toString()
}
