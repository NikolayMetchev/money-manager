@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package com.moneymanager.domain.model

import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi

data class MonzoCredential(
    val id: MonzoCredentialId,
    val type: ApiSessionType,
    val token: String,
    val createdAt: Instant,
    val strategyId: ApiImportStrategyId? = null,
    /** PEM-encoded RSA keys for request signing (e.g. Wise SCA); null when not configured. */
    val privateKey: String? = null,
    val publicKey: String? = null,
) {
    override fun toString(): String =
        "MonzoCredential(id=$id, type=$type, token=<redacted>, createdAt=$createdAt, " +
            "strategyId=$strategyId, privateKey=${if (privateKey != null) "<redacted>" else "null"}, " +
            "publicKey=${if (publicKey != null) "<redacted>" else "null"})"
}

@JvmInline
value class MonzoCredentialId(
    val id: Long,
) {
    override fun toString() = id.toString()
}

data class ApiSession(
    val id: ApiSessionId,
    val type: ApiSessionType,
    val token: String,
    val deviceId: DeviceId,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val credentialId: MonzoCredentialId?,
    val importDurationMillis: Long? = null,
)

enum class ApiSessionType(
    val id: Long,
) {
    MONZO(1),
    ;

    companion object {
        fun fromId(id: Long): ApiSessionType =
            entries.firstOrNull { it.id == id }
                ?: error("Unknown API session type id: $id")
    }
}

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
