package com.moneymanager.database.repository

/** SQLite's default variable limit is 999; batched IN lookups chunk their ids to stay under it. */
internal const val MAX_IDS_PER_QUERY = 999

/**
 * Chunk size for queries that bind the same id list twice (`... IN :ids OR ... IN :ids`) —
 * SQLDelight expands the collection once per use, so each id costs two bind variables.
 */
internal const val MAX_IDS_PER_TWO_SIDED_QUERY = MAX_IDS_PER_QUERY / 2
