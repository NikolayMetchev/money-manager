package com.moneymanager.domain.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * A connected API: the database's non-secret anchor for one strategy's download sessions. The token,
 * api secret and signing keys live in the encrypted credential vault, keyed by strategy name.
 */
data class ApiCredential(
    val id: ApiCredentialId,
    val createdAt: Instant,
    val strategyId: ApiImportStrategyId? = null,
)

@JvmInline
value class ApiCredentialId(
    val id: Long,
) {
    override fun toString() = id.toString()
}

data class ApiSession(
    val id: ApiSessionId,
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
