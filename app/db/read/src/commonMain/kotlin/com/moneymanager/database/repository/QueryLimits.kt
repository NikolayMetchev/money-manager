package com.moneymanager.database.repository

/** SQLite's default variable limit is 999; batched IN lookups chunk their ids to stay under it. */
internal const val MAX_IDS_PER_QUERY = 999
