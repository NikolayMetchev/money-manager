package com.moneymanager.credentialvault

import kotlinx.coroutines.flow.StateFlow

/** Where the vault stands for the currently bound database. */
sealed interface VaultState {
    /** No database is bound yet, so there is no file location to work with. */
    data object Unbound : VaultState

    /** Bound, but no vault file exists at [path] yet; the first secret saved will create it. */
    data class NoFile(
        val path: String,
    ) : VaultState

    data class Locked(
        val path: String,
    ) : VaultState

    data class Unlocked(
        val path: String,
        val bundle: CredentialBundle,
    ) : VaultState
}

/** A pending request, from code that needs a secret, for the UI to ask the user to unlock or create the vault. */
data class UnlockRequest(
    val reason: String,
)

/** Thrown to a caller of [CredentialVault.requireUnlocked] when the user declines to unlock. */
class CredentialVaultLockedException(
    message: String = "Credentials are locked",
) : Exception(message)

/**
 * The encrypted, password-protected local file that holds API tokens and Google sign-ins, so they
 * survive the database being wiped.
 *
 * The password is held **in memory only**, for the life of the unlocked session; it is never written to
 * the database, local settings or logs. Unlocking is lazy: code that needs a secret calls
 * [requireUnlocked], which raises an [UnlockRequest] for the UI to answer.
 */
interface CredentialVault {
    val state: StateFlow<VaultState>

    /** Non-null while some caller is waiting in [requireUnlocked] for the user to unlock. */
    val unlockRequest: StateFlow<UnlockRequest?>

    /**
     * Points the vault at the file for the database identified by [databaseKey] (its location string).
     * A different file locks the vault and forgets the in-memory password; the same file is a no-op.
     */
    fun bindToDatabase(databaseKey: String)

    /** The location a new vault file is offered at for the bound database (next to it), or null if unbound. */
    fun defaultPath(): String?

    /** Creates a new, empty vault at [path] protected by [password], binds to it and unlocks it. */
    suspend fun create(
        path: String,
        password: String,
    )

    /**
     * Unlocks the vault file at [path] (default: the bound one) with [password], remembering [path] for the
     * bound database. Throws `ArchiveDecryptionException` on a wrong password or unreadable file.
     */
    suspend fun unlock(
        password: String,
        path: String? = null,
    )

    /** Forgets the password and decrypted secrets until the next unlock. Waits for an in-flight write to finish. */
    suspend fun lock()

    /** Re-encrypts the unlocked vault under [newPassword]. */
    suspend fun changePassword(newPassword: String)

    /** Moves the unlocked vault to [newPath] and remembers that location for the bound database. */
    suspend fun moveTo(newPath: String)

    /**
     * Returns the decrypted secrets, first asking the UI to unlock (or create) the vault if needed and
     * suspending until it does. Throws [CredentialVaultLockedException] if the user cancels.
     */
    suspend fun requireUnlocked(reason: String): CredentialBundle

    /** Declines the pending [unlockRequest]; its waiters fail with [CredentialVaultLockedException]. */
    fun cancelUnlockRequest()

    /** Applies [transform] to the secrets and writes the vault, unlocking first via [requireUnlocked]. */
    suspend fun update(
        reason: String,
        transform: (CredentialBundle) -> CredentialBundle,
    )

    /**
     * The bound vault file's raw, still-encrypted bytes (as backed up to remote storage), or null when no
     * file exists yet. Never needs unlocking: the bytes are useless without the password.
     */
    suspend fun encryptedBytes(): ByteArray?

    /**
     * Takes in a backed-up copy of the vault: decrypts [bytes] with [password] (default: the in-memory one)
     * and either replaces the unlocked secrets with it or, when [merge] is true, folds it in via
     * [CredentialBundle.mergedWith] (this side preferred). When [password] differs from the current one, the
     * local file is re-encrypted under it, so every device sharing the backup converges on one password.
     *
     * Requires the vault to be unlocked. Throws `ArchiveDecryptionException` if [password] is wrong.
     */
    suspend fun applyRemote(
        bytes: ByteArray,
        password: String?,
        merge: Boolean,
    )
}

/** The decrypted secrets if the vault is already unlocked, without prompting. */
fun CredentialVault.bundleOrNull(): CredentialBundle? = (state.value as? VaultState.Unlocked)?.bundle
