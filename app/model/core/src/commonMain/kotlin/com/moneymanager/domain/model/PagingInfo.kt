@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

data class PagingInfo(
    val lastTimestamp: Instant?,
    val lastId: TransactionId?,
    val hasMore: Boolean,
)

data class PagingResult<T>(
    val items: List<T>,
    val pagingInfo: PagingInfo,
)
