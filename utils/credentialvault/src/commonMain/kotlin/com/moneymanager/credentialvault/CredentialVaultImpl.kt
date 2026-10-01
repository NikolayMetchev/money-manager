package com.moneymanager.credentialvault

import com.moneymanager.archive.ArchiveCodec
import com.moneymanager.archive.ArchiveDecryptionException
import com.moneymanager.localsettings.LocalSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Raw byte access to vault files, so the vault logic stays platform-agnostic (and testable in memory). */
interface VaultStorage {
    fun exists(path: String): Boolean

    fun read(path: String): ByteArray

    /** Replaces the file at [path] with [bytes] so a crash mid-write never leaves a truncated vault. */
    fun writeAtomically(
        path: String,
        bytes: ByteArray,
    )

    fun delete(path: String)
}

/** Where a database's vault file goes by default: next to the database. */
fun interface VaultPathResolver {
    fun defaultPathFor(databaseKey: String): String
}

/**
 * [CredentialVault] over [ArchiveCodec] (Deflate → PBKDF2-SHA256 → AES-GCM), so the vault shares the
 * cloud archive's encryption instead of rolling its own. A user-chosen location is remembered in
 * [localSettings] per database; the password never is.
 */
class CredentialVaultImpl(
    private val storage: VaultStorage,
    private val pathResolver: VaultPathResolver,
    private val localSettings: LocalSettings,
) : CredentialVault {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<VaultState>(VaultState.Unbound)
    private val mutableUnlockRequest = MutableStateFlow<UnlockRequest?>(null)

    // Bumped on every cancel. A StateFlow (not an event stream) so a waiter that subscribes just after a
    // cancel still sees it, rather than waiting forever on an emission it missed.
    private val cancellations = MutableStateFlow(0)
    private var databaseKey: String? = null
    private var password: String? = null

    override val state: StateFlow<VaultState> = mutableState.asStateFlow()
    override val unlockRequest: StateFlow<UnlockRequest?> = mutableUnlockRequest.asStateFlow()

    override fun bindToDatabase(databaseKey: String) {
        this.databaseKey = databaseKey
        val path = localSettings.getString(locationKey(databaseKey)) ?: pathResolver.defaultPathFor(databaseKey)
        if (currentPath() == path && mutableState.value !is VaultState.NoFile) return
        password = null
        mutableState.value = lockedStateFor(path)
    }

    override fun defaultPath(): String? = databaseKey?.let(pathResolver::defaultPathFor)

    override suspend fun create(
        path: String,
        password: String,
    ) {
        require(password.isNotEmpty()) { "Password must not be empty" }
        mutex.withLock {
            require(!storage.exists(path)) { "A credential file already exists at $path" }
            val bundle = CredentialBundle()
            write(path, bundle, password)
            rememberPath(path)
            this.password = password
            mutableState.value = VaultState.Unlocked(path, bundle)
            mutableUnlockRequest.value = null
        }
    }

    override suspend fun unlock(
        password: String,
        path: String?,
    ) {
        require(password.isNotEmpty()) { "Password must not be empty" }
        mutex.withLock {
            val target = path ?: requireNotNull(currentPath()) { "No database is open" }
            if (!storage.exists(target)) throw ArchiveDecryptionException("No credential file at $target")
            val bundle = decode(ArchiveCodec.unpack(storage.read(target), password))
            rememberPath(target)
            this.password = password
            mutableState.value = VaultState.Unlocked(target, bundle)
            mutableUnlockRequest.value = null
        }
    }

    override suspend fun lock() {
        // Under the write lock, so an in-flight update can't republish Unlocked after the password is forgotten.
        mutex.withLock {
            val path = currentPath() ?: return
            password = null
            mutableState.value = lockedStateFor(path)
        }
    }

    override suspend fun changePassword(newPassword: String) {
        require(newPassword.isNotEmpty()) { "Password must not be empty" }
        mutex.withLock {
            val unlocked = requireUnlockedState()
            write(unlocked.path, unlocked.bundle, newPassword)
            password = newPassword
        }
    }

    override suspend fun moveTo(newPath: String) {
        mutex.withLock {
            val unlocked = requireUnlockedState()
            if (newPath == unlocked.path) return
            require(!storage.exists(newPath)) { "A file already exists at $newPath" }
            write(newPath, unlocked.bundle, requireNotNull(password))
            storage.delete(unlocked.path)
            rememberPath(newPath)
            mutableState.value = VaultState.Unlocked(newPath, unlocked.bundle)
        }
    }

    override suspend fun requireUnlocked(reason: String): CredentialBundle {
        when (val current = mutableState.value) {
            is VaultState.Unlocked -> return current.bundle
            VaultState.Unbound -> throw CredentialVaultLockedException("No database is open, so there is no credential file")
            else -> Unit
        }
        val cancelsBefore = cancellations.value
        // Concurrent callers share the first request; its reason is what the user sees.
        mutableUnlockRequest.compareAndSet(null, UnlockRequest(reason))
        return combine(mutableState, cancellations) { state, cancels ->
            when {
                state is VaultState.Unlocked -> Result.success(state.bundle)
                cancels != cancelsBefore -> Result.failure(CredentialVaultLockedException())
                else -> null
            }
        }.filterNotNull()
            .first()
            .getOrThrow()
    }

    override fun cancelUnlockRequest() {
        mutableUnlockRequest.value = null
        cancellations.value += 1
    }

    override suspend fun update(
        reason: String,
        transform: (CredentialBundle) -> CredentialBundle,
    ) {
        requireUnlocked(reason)
        mutex.withLock {
            // Re-read under the lock: another writer may have changed the bundle since requireUnlocked.
            val unlocked = requireUnlockedState()
            val updated = transform(unlocked.bundle)
            if (updated == unlocked.bundle) return
            write(unlocked.path, updated, requireNotNull(password))
            mutableState.value = VaultState.Unlocked(unlocked.path, updated)
        }
    }

    private fun currentPath(): String? =
        when (val current = mutableState.value) {
            VaultState.Unbound -> null
            is VaultState.NoFile -> current.path
            is VaultState.Locked -> current.path
            is VaultState.Unlocked -> current.path
        }

    private fun lockedStateFor(path: String): VaultState = if (storage.exists(path)) VaultState.Locked(path) else VaultState.NoFile(path)

    private fun requireUnlockedState(): VaultState.Unlocked =
        mutableState.value as? VaultState.Unlocked ?: throw CredentialVaultLockedException()

    private suspend fun write(
        path: String,
        bundle: CredentialBundle,
        password: String,
    ) {
        storage.writeAtomically(path, ArchiveCodec.pack(json.encodeToString(bundle).encodeToByteArray(), password))
    }

    private fun decode(plain: ByteArray): CredentialBundle {
        val bundle =
            try {
                json.decodeFromString<CredentialBundle>(plain.decodeToString())
            } catch (expected: SerializationException) {
                throw ArchiveDecryptionException("Not a Money Manager credential file", expected)
            }
        if (bundle.version > CredentialBundle.CURRENT_VERSION) {
            throw ArchiveDecryptionException("This credential file was written by a newer version of Money Manager")
        }
        return bundle
    }

    // Only a location that differs from the default is worth remembering; clearing it otherwise lets the
    // default follow the database if the user moves both together.
    private fun rememberPath(path: String) {
        val key = databaseKey ?: return
        if (path == pathResolver.defaultPathFor(key)) {
            localSettings.remove(locationKey(key))
        } else {
            localSettings.putString(locationKey(key), path)
        }
    }

    private companion object {
        const val MAX_RADIX = 36
        val json: Json = Json { ignoreUnknownKeys = true }

        // Hashed rather than raw: a database path easily exceeds java.util.prefs' 80-char key limit.
        fun locationKey(databaseKey: String) = "credvault.path.${databaseKey.hashCode().toUInt().toString(MAX_RADIX)}"
    }
}

/** The platform's file-system [VaultStorage]. */
expect fun fileVaultStorage(): VaultStorage
