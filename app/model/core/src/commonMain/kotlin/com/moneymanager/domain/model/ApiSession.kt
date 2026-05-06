@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model

import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import kotlin.time.Instant

data class MonzoCredential(
    val id: MonzoCredentialId,
    val type: ApiSessionType,
    val token: String,
    val createdAt: Instant,
    val strategyId: ApiImportStrategyId? = null,
)

@JvmInline
value class MonzoCredentialId(
    val id: Long,
) {
    override fun toString() = id.toString()
}

enum class ApiSessionKind(
    val value: String,
) {
    ACCOUNTS("ACCOUNTS"),
    TRANSACTIONS("TRANSACTIONS"),
    ;

    companion object {
        fun fromValueOrNull(value: String?): ApiSessionKind? = value?.let { v -> entries.firstOrNull { it.value == v } }
    }
}

data class ApiSession(
    val id: ApiSessionId,
    val type: ApiSessionType,
    val token: String,
    val deviceId: DeviceId,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val credentialId: MonzoCredentialId?,
    val kind: ApiSessionKind?,
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

@JvmInline
value class ApiSessionId(
    val id: Long,
) {
    override fun toString() = id.toString()
}
