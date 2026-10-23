package com.moneymanager.session

import co.touchlab.kermit.Logger
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.di.AppServices
import com.moneymanager.database.di.DatabaseComponent
import com.moneymanager.database.di.createImportEngine
import com.moneymanager.database.di.openDatabaseComponent
import com.moneymanager.database.di.toAppServices
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.importengineapi.EditGate
import com.moneymanager.importengineapi.EditingLockedException
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.localsettings.KEY_LAST_DATABASE
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.remotestorage.sync.RemoteDatabaseBinding
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.tasks.Progress

private val logger = Logger.withTag("DatabaseSessions")

/** An open database and everything that works against it. */
data class DatabaseSession(
    val location: DbLocation,
    val database: MoneyManagerDatabaseWrapper,
    val component: DatabaseComponent,
    /** The single write seam, carrying the [DatabaseSessions.editGate]. */
    val importEngine: ImportEngine,
    /** Read-only services; [AppServices.deviceId] is already resolved. */
    val services: AppServices,
)

/** What to do at startup, decided by [DatabaseSessions.resolveStartup]. */
sealed interface StartupTarget {
    /** Open [location]; [remoteCache] is true when it is the kept working copy of a cloud-backed database. */
    data class Open(
        val location: DbLocation,
        val remoteCache: Boolean = false,
    ) : StartupTarget

    /** A cloud-backed database with no usable local copy: restoring it needs the user's password. */
    data class NeedsRemoteUnlock(
        val binding: RemoteDatabaseBinding,
    ) : StartupTarget

    /** No remembered database and no remote binding, so the user must choose one. */
    data object ChooseDatabase : StartupTarget
}

/**
 * Opens databases without any UI: decides which one to open at startup, opens it, and builds the
 * session's [ImportEngine] and read services. The desktop app, Android and the CLI share it, so the only
 * thing a front end adds is how it asks the user (where to create a database, the restore password).
 *
 * Every open binds [credentialVault] to the database, remembers it as [KEY_LAST_DATABASE], and, for a
 * cloud-backed database, captures [remoteController]'s sync baseline.
 */
class DatabaseSessions(
    private val databaseManager: DatabaseManager,
    private val localSettings: LocalSettings,
    private val credentialVault: CredentialVault,
    private val remoteController: RemoteDatabaseController? = null,
) {
    /** Refuses writes while the cloud copy holds changes this device hasn't pulled. */
    val editGate: EditGate =
        EditGate {
            if (remoteController?.syncState?.value?.editingLocked == true) throw EditingLockedException()
        }

    /**
     * Decides what to open. An [explicit] location wins; otherwise the remote binding's kept working copy,
     * then a password restore of the binding, then the last database used (if it still exists). The vault
     * is bound to the expected database first, since restoring a cloud-backed one already needs the
     * Google sign-in it holds.
     */
    suspend fun resolveStartup(explicit: DbLocation? = null): StartupTarget {
        if (explicit != null) {
            credentialVault.bindToDatabase(explicit.toString())
            return StartupTarget.Open(explicit)
        }
        val binding = remoteController?.activeBinding()
        val stored = localSettings.getString(KEY_LAST_DATABASE)
        credentialVault.bindToDatabase(
            binding?.localCachePath ?: stored ?: databaseManager.getDefaultLocation().toString(),
        )
        if (binding != null) {
            // A working copy kept on the previous close opens directly: no password, no download, no network.
            val cacheLocation = DbLocation(binding.localCachePath)
            return if (databaseManager.databaseExists(cacheLocation)) {
                StartupTarget.Open(cacheLocation, remoteCache = true)
            } else {
                StartupTarget.NeedsRemoteUnlock(binding)
            }
        }
        // A corrupted persisted value (e.g. an invalid path) doesn't exist, so it's treated as absent.
        val storedLocation = stored?.let(::DbLocation)
        return if (storedLocation != null && databaseManager.databaseExists(storedLocation)) {
            StartupTarget.Open(storedLocation)
        } else {
            StartupTarget.ChooseDatabase
        }
    }

    /**
     * Opens [location] (creating and seeding it if missing) and builds its session. Pass [remoteCache]
     * when it is the kept working copy of the bound cloud database, so its dirty baseline is seeded from
     * the persisted binding. Throws on failure, with the database closed again.
     */
    suspend fun open(
        location: DbLocation,
        remoteCache: Boolean = false,
        onProgress: (Progress) -> Unit = {},
    ): DatabaseSession {
        logger.i { "Opening database at: $location" }
        val database = databaseManager.openDatabaseWithProgress(location, onProgress)
        return try {
            onProgress(Progress.counted("Preparing application services...", 1, 1))
            val component = openDatabaseComponent(database)
            val importEngine = component.createImportEngine(editGate)
            val services = component.toAppServices(importEngine)
            onProgress(Progress.counted("Verifying this device...", 1, 1))
            services.deviceId
            credentialVault.bindToDatabase(location.toString())
            if (remoteController != null) {
                if (remoteCache) {
                    // Lets the close dialog tell whether the local copy changed since its last upload.
                    remoteController.adoptLocalCache(database)
                } else if (remoteController.hasActiveSession() && !remoteController.hasSyncBaseline()) {
                    // A freshly hydrated cloud database: this is the "everything synced" baseline.
                    remoteController.markSynced(database)
                }
            }
            localSettings.putString(KEY_LAST_DATABASE, location.toString())
            logger.i { "Database opened successfully" }
            DatabaseSession(location, database, component, importEngine, services)
        } catch (expected: Exception) {
            // Cancellation included: a half-built session must not keep the file open.
            database.closeQuietly()
            throw expected
        }
    }

    /**
     * Restores the bound cloud database with [password] and opens it. A
     * [com.moneymanager.remotestorage.RemoteAuthException] means the cloud sign-in expired, not that the
     * password was wrong.
     */
    suspend fun restoreRemote(
        binding: RemoteDatabaseBinding,
        password: String,
        onProgress: (Progress) -> Unit = {},
    ): DatabaseSession {
        val controller = requireNotNull(remoteController) { "Restoring a cloud database needs a remote controller" }
        val location = controller.restore(binding, password, onProgress)
        return open(location, onProgress = onProgress)
    }

    /**
     * Re-downloads the bound cloud database over [current]'s working copy and reopens it. [current] is
     * closed first, since restoring over a still-open handle fails as busy/locked (notably on Android).
     */
    suspend fun reloadFromRemote(
        current: DatabaseSession,
        onProgress: (Progress) -> Unit = {},
    ): DatabaseSession {
        val controller = requireNotNull(remoteController) { "Reloading a cloud database needs a remote controller" }
        current.database.close()
        val location = controller.download(onProgress)
        return open(location, onProgress = onProgress)
    }

    /** Replaces the database at [location] with a fresh one, keeping a [backup] of the old file or deleting it. */
    suspend fun recreate(
        location: DbLocation,
        backup: Boolean,
        onProgress: (Progress) -> Unit = {},
    ): DatabaseSession {
        if (backup) {
            logger.i { "Backing up database and creating new one..." }
            val backupLocation = databaseManager.backupDatabase(location)
            logger.i { "Database backed up to: $backupLocation" }
        } else {
            logger.i { "Deleting database and creating new one..." }
            databaseManager.deleteDatabase(location)
        }
        return open(location, onProgress = onProgress)
    }
}

private fun MoneyManagerDatabaseWrapper.closeQuietly() {
    runCatching { close() }.onFailure { logger.w(it) { "Failed to close database after a failed open" } }
}
