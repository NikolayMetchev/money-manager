@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

data class ApiSession(
    val id: ApiSessionId,
    val type: ApiSessionType,
    val token: String,
    val deviceId: DeviceId,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val revokedAt: Instant?,
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
    val json: String,
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
