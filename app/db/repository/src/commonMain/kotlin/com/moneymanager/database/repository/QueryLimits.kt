package com.moneymanager.database.repository

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** SQLite's default variable limit is 999; batched IN lookups chunk their ids to stay under it. */
internal const val MAX_IDS_PER_QUERY = 999

/**
 * Chunk size for queries that bind the same id list twice (`... IN :ids OR ... IN :ids`) —
 * SQLDelight expands the collection once per use, so each id costs two bind variables.
 */
internal const val MAX_IDS_PER_TWO_SIDED_QUERY = MAX_IDS_PER_QUERY / 2

/**
 * How often a whole-table listing re-runs while writes keep changing it (see `throttleLatest`): a bulk import
 * commits hundreds of times, and each commit used to re-run every import listing and timeline aggregate.
 */
val LISTING_REFRESH_PERIOD: Duration = 250.milliseconds

/** Runs [query] over the distinct [ids] in chunks of [chunkSize] and joins the rows, in chunk order. */
internal suspend fun <T> queryInChunks(
    ids: Iterable<Long>,
    chunkSize: Int,
    query: suspend (List<Long>) -> List<T>,
): List<T> {
    val rows = mutableListOf<T>()
    for (chunk in ids.distinct().chunked(chunkSize)) rows += query(chunk)
    return rows
}
