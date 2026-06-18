package com.moneymanager.remotestorage.sync

import app.cash.sqldelight.db.QueryResult
import com.moneymanager.database.MoneyManagerDatabaseWrapper
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
    private val files = mutableMapOf<String, Pair<String, ByteArray>>()
    private var counter = 0
    private var signedIn = true

    override suspend fun isSignedIn(): Boolean = signedIn

    override suspend fun signIn() {
        signedIn = true
    }

    override suspend fun signOut() {
        signedIn = false
    }

    override suspend fun list(): List<RemoteFile> = files.map { (id, value) -> RemoteFile(id, value.first, value.second.size.toLong()) }

    override suspend fun download(fileId: String): ByteArray =
        files[fileId]?.second ?: throw RemoteStorageException("No such file: $fileId")

    override suspend fun upload(
        fileId: String?,
        name: String,
        bytes: ByteArray,
    ): RemoteFile {
        val id = fileId ?: "file-${counter++}"
        files[id] = name to bytes
        return RemoteFile(id, name, bytes.size.toLong())
    }

    override suspend fun delete(fileId: String) {
        files.remove(fileId)
    }
}

/** A [RemoteStorageProviderFactory] that always hands back the same [provider] instance. */
class SingleProviderFactory(
    private val provider: RemoteStorageProvider,
) : RemoteStorageProviderFactory {
    override fun types(): List<RemoteStorageType> = listOf(RemoteStorageType(provider.id, provider.displayName))

    override fun create(
        providerId: String,
        config: String?,
    ): RemoteStorageProvider = provider
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
