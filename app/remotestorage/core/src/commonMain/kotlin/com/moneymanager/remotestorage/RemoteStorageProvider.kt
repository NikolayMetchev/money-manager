package com.moneymanager.remotestorage

/**
 * A remote storage backend (e.g. Google Drive) that can hold an encrypted database archive.
 *
 * The contract is intentionally backend-agnostic and database-agnostic: callers hand over and receive
 * opaque [ByteArray] payloads (produced by `utils/archive`), so a single implementation per provider
 * serves every use case and new providers (OneDrive, Dropbox, iDrive, ...) slot in behind this type.
 *
 * Implementations are platform-specific where they must be (authentication, native SDKs) but expose
 * only this common surface. All operations are `suspend` and may perform network I/O.
 */
interface RemoteStorageProvider {
    /** Stable identifier persisted in local settings to re-bind a database on next launch (e.g. "google-drive"). */
    val id: String

    /** Human-readable name for UI (e.g. "Google Drive"). */
    val displayName: String

    /**
     * Returns true if a usable session already exists (e.g. a cached OAuth token) without prompting
     * the user. Used on startup to decide whether a remote-bound database can be restored silently.
     */
    suspend fun isSignedIn(): Boolean

    /**
     * Performs interactive sign-in, which may open a browser or account picker. Returns normally on
     * success; throws [RemoteAuthException] if the user cancels or authentication fails.
     */
    suspend fun signIn()

    /** Clears any cached session/credentials for this provider. */
    suspend fun signOut()

    /** Lists the database archives this app has stored in the provider. */
    suspend fun list(): List<RemoteFile>

    /**
     * Returns the descriptor (including [RemoteFile.sizeBytes]) for [fileId], or null if it's absent.
     * The default scans [list]; providers should override when a cheaper direct lookup exists.
     */
    suspend fun stat(fileId: String): RemoteFile? = list().firstOrNull { it.id == fileId }

    /** Downloads the raw archive bytes for [fileId]. */
    suspend fun download(fileId: String): ByteArray

    /**
     * Uploads [bytes] as [name]. When [fileId] is null a new remote file is created; otherwise the
     * existing file is overwritten in place (so its id stays stable across syncs).
     *
     * @return the descriptor of the created/updated remote file
     */
    suspend fun upload(
        fileId: String?,
        name: String,
        bytes: ByteArray,
    ): RemoteFile

    /** Deletes the remote file [fileId]. */
    suspend fun delete(fileId: String)

    /**
     * For token-based backends, the epoch-millis instant the cached access token expires (after which
     * it is refreshed silently), or null if not applicable / unknown. Lets the UI show when the local
     * session token will next refresh. Does not perform any network I/O.
     */
    suspend fun accessTokenExpiresAtEpochMs(): Long? = null
}

/** Descriptor for a remote database archive. */
data class RemoteFile(
    val id: String,
    val name: String,
    val sizeBytes: Long? = null,
    val modifiedAtEpochMs: Long? = null,
)
