package com.moneymanager.remotestorage

/** A remote-storage backend offered by this build, for selection in the UI. */
data class RemoteStorageType(
    val id: String,
    val displayName: String,
)

/**
 * Builds [RemoteStorageProvider] instances. Lets the UI enumerate available backends and lets startup
 * reconstruct the bound provider from a persisted id + provider-specific `config`, without the common
 * code depending on platform-specific provider implementations.
 */
interface RemoteStorageProviderFactory {
    /** The backends available in this build. */
    fun types(): List<RemoteStorageType>

    /**
     * Creates the provider for [providerId], using the provider-specific [config] persisted in the
     * database binding (e.g. the Google Drive OAuth client credentials).
     *
     * When [subfolder] is set, the provider scopes its files to that named namespace within the
     * backend's app area (e.g. the Google Drive "Money Manager/[subfolder]" folder), isolating them
     * from the top-level DB archives. Null uses the top-level app area.
     *
     * @throws IllegalArgumentException if [providerId] is unknown to this build
     */
    fun create(
        providerId: String,
        config: String?,
        subfolder: String? = null,
    ): RemoteStorageProvider
}

/**
 * Forces fresh interactive consent for [providerId]/[config], minting a new refresh token. Unlike a
 * normal resolve (create-then-`signIn()`-only-if-not-`isSignedIn()`), this does NOT short-circuit on an
 * existing stored token: a token the provider still reports as "signed in" can be expired or revoked
 * (Google only rejects it with `invalid_grant` when it is actually used), so recovering from such a
 * failure requires re-running consent unconditionally. Reusable by any remote-storage connection (DB
 * archives, the strategy library, future backends).
 *
 * [subfolder] is irrelevant to the OAuth consent itself but accepted for a uniform call surface.
 */
suspend fun RemoteStorageProviderFactory.reconnect(
    providerId: String,
    config: String?,
    subfolder: String? = null,
) = create(providerId, config, subfolder).signIn()
