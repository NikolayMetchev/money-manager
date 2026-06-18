package com.moneymanager.remotestorage

/** A remote-storage backend offered by this build, for selection in the UI. */
data class RemoteStorageType(
    val id: String,
    val displayName: String,
    /** True if the backend is bound to a user-chosen local directory (e.g. an OS-synced folder). */
    val requiresFolder: Boolean = false,
)

/**
 * Builds [RemoteStorageProvider] instances. Lets the UI enumerate available backends and lets startup
 * reconstruct the bound provider from a persisted id + [config] (e.g. a folder path), without the
 * common code depending on platform-specific provider implementations.
 */
interface RemoteStorageProviderFactory {
    /** The backends available in this build. */
    fun types(): List<RemoteStorageType>

    /**
     * Creates the provider for [providerId], using the provider-specific [config] persisted in the
     * database binding (a folder path for folder-backed providers; ignored by others).
     *
     * @throws IllegalArgumentException if [providerId] is unknown to this build
     */
    fun create(providerId: String, config: String?): RemoteStorageProvider
}
