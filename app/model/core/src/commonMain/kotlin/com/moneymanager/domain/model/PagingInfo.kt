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

/** Result of loading a page centered around a specific item. */
data class PageWithTargetIndex<T>(
    val items: List<T>,
    val pagingInfo: PagingInfo,
    /** True if there are items before this page (newer items with higher timestamps) */
    val hasPrevious: Boolean,
)
