package com.moneymanager.remotestorage.sync

import app.cash.sqldelight.db.QueryResult
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.remotestorage.RemoteFile
import com.moneymanager.remotestorage.RemoteStorageException
import com.moneymanager.remotestorage.RemoteStorageProvider
import com.moneymanager.remotestorage.RemoteStorageProviderFactory
import com.moneymanager.remotestorage.RemoteStorageType

/** In-memory [RemoteStorageProvider] standing in for a real backend in tests (no network/OAuth). */
class InMemoryStorageProvider(
    override val id: String = "in-memory",
    override val displayName: String = "In-Memory",
) : RemoteStorageProvider {
    // Not a data class: a ByteArray member would need custom equals/hashCode, which we don't rely on.
    private class Entry(
        val name: String,
        val bytes: ByteArray,
        val revision: Int,
    )

    private val files = mutableMapOf<String, Entry>()
    private var counter = 0

    // Monotonic, like Drive's headRevisionId: every content write yields a brand-new revision id.
    private var revisionCounter = 0
    private var signedIn = true

    override suspend fun isSignedIn(): Boolean = signedIn

    override suspend fun signIn() {
        signedIn = true
    }

    override suspend fun signOut() {
        signedIn = false
    }

    override suspend fun list(): List<RemoteFile> = files.map { (id, entry) -> entry.toRemoteFile(id) }

    override suspend fun stat(fileId: String): RemoteFile? = files[fileId]?.toRemoteFile(fileId)

    // Copy on the way in/out so a caller mutating its array can't silently change stored content (which
    // would otherwise alter a file without bumping its revision and break the sync tests).
    override suspend fun download(fileId: String): ByteArray =
        files[fileId]?.bytes?.copyOf() ?: throw RemoteStorageException("No such file: $fileId")

    override suspend fun upload(
        fileId: String?,
        name: String,
        bytes: ByteArray,
    ): RemoteFile {
        val id = fileId ?: "file-${counter++}"
        val entry = Entry(name, bytes.copyOf(), revisionCounter++)
        files[id] = entry
        return entry.toRemoteFile(id)
    }

    override suspend fun delete(fileId: String) {
        files.remove(fileId)
    }

    /** Test seam: simulate another device pushing new content to [fileId] (bumps its revision). */
    fun externalPush(
        fileId: String,
        bytes: ByteArray,
    ) {
        val existing = files[fileId] ?: error("No such file to externally push: $fileId")
        files[fileId] = Entry(existing.name, bytes.copyOf(), revisionCounter++)
    }

    private fun Entry.toRemoteFile(id: String) = RemoteFile(id, name, bytes.size.toLong(), revisionId = "rev-$revision")
}

/** A [RemoteStorageProviderFactory] that always hands back the same [provider] instance. */
class SingleProviderFactory(
    private val provider: RemoteStorageProvider,
) : RemoteStorageProviderFactory {
    override fun types(): List<RemoteStorageType> = listOf(RemoteStorageType(provider.id, provider.displayName))

    override fun create(
        providerId: String,
        config: String?,
        subfolder: String?,
    ): RemoteStorageProvider {
        require(providerId == provider.id) { "Unknown providerId: $providerId" }
        return provider
    }
}

/** Counts rows in [table] (test helper). */
fun MoneyManagerDatabaseWrapper.countRows(table: String): Long =
    executeQuery(
        identifier = null,
        sql = "SELECT COUNT(*) FROM $table",
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0)!!)
        },
        parameters = 0,
    ).value

/**
 * Appends an audit row so [MoneyManagerDatabaseWrapper.dataChangeToken] advances, simulating a local
 * edit (the controller's "local dirty" signal) without going through the repositories.
 */
fun MoneyManagerDatabaseWrapper.bumpDataChange() {
    execute(
        identifier = null,
        sql =
            "INSERT INTO category_audit (audit_timestamp, audit_type_id, category_id, revision_id, name) " +
                "VALUES (0, (SELECT id FROM audit_type LIMIT 1), 1, 1, 'dirty')",
        parameters = 0,
    )
}

/** In-memory [LocalSettings] for tests. */
class InMemoryLocalSettings : LocalSettings {
    private val map = mutableMapOf<String, String>()

    override fun getString(key: String): String? = map[key]

    override fun putString(
        key: String,
        value: String,
    ) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }
}
