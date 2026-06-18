package com.moneymanager.domain.model

/**
 * The local working-copy [DbLocation] for a database opened from remote storage with archive [name].
 * The hydrated copy lives here and is what the app actually runs against.
 */
expect fun remoteCacheLocation(name: String): DbLocation
