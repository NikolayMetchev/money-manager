package com.moneymanager.remotestorage

/** A remote-storage backend offered by this build, for selection in the UI. */
data class RemoteStorageType(
    val id: String,
    val displayName: String,
)

/**
 * Builds [RemoteStorageProvider] instances. Lets the UI enumerate available backends and lets startup
 * reconstruct the bound provider from a persisted id + provider-specific [config], without the common
 * code depending on platform-specific provider implementations.
 */
interface RemoteStorageProviderFactory {
    /** The backends available in this build. */
    fun types(): List<RemoteStorageType>

    /**
     * Creates the provider for [providerId], using the provider-specific [config] persisted in the
     * database binding (e.g. the Google Drive OAuth client credentials).
     *
     * @throws IllegalArgumentException if [providerId] is unknown to this build
     */
    fun create(
        providerId: String,
        config: String?,
    ): RemoteStorageProvider
}
