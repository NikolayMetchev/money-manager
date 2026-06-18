package com.moneymanager.domain.model

/**
 * The local working-copy [DbLocation] for a database opened from remote storage with archive [name].
 * The hydrated copy lives here and is what the app actually runs against. The archive name may carry a
 * compressed+encrypted extension (e.g. `.mmenc`); the local copy is always a real SQLite `.db` file.
 */
expect fun remoteCacheLocation(name: String): DbLocation
