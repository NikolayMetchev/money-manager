@file:OptIn(ExperimentalWasmJsInterop::class)

package com.moneymanager.database

import com.moneymanager.currency.IsoCurrency
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.DEFAULT_DATABASE_NAME
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.tasks.Progress
import org.khronos.webgl.toByteArray
import org.khronos.webgl.toInt8Array
import kotlin.js.ExperimentalWasmJsInterop

/**
 * The browser's [DatabaseManager]: databases are files in the SQLite worker's store (the origin private file
 * system), named by [DbLocation.path], and every statement goes over [channel].
 *
 * As on the other platforms, **create-vs-open is decided by whether the file exists**, and a failed creation
 * deletes the file. There are no WAL sidecars: the worker's VFS keeps a rollback journal of its own.
 */
class WebDatabaseManager(
    private val channel: SqliteChannel,
    private val currenciesToSeed: () -> List<IsoCurrency> = { DatabaseConfig.allCurrencies },
) : DatabaseManager {
    override suspend fun openDatabase(location: DbLocation): MoneyManagerDatabaseWrapper = openDatabaseWithProgress(location) {}

    override suspend fun openDatabaseWithProgress(
        location: DbLocation,
        onProgress: (Progress) -> Unit,
    ): MoneyManagerDatabaseWrapper {
        onProgress(Progress.counted("Checking for an existing database...", 1, OPEN_STEPS))
        val isNewDatabase = !databaseExists(location)

        onProgress(Progress.counted("Opening the SQLite database...", 3, OPEN_STEPS))
        request("open", location)
        val driver = WebSqliteDriver(channel, location.path)
        val database = MoneyManagerDatabaseWrapper(driver)
        DatabaseConfig.connectionPragmas.forEach { driver.execute(null, it, 0).await() }
        if (isNewDatabase) {
            var created = false
            try {
                createSchema(database, currenciesToSeed, onProgress)
                created = true
            } finally {
                if (!created) request("delete", location)
            }
        } else {
            reportExisting(onProgress)
        }
        onProgress(Progress.counted("Finishing database startup...", OPEN_STEPS, OPEN_STEPS))
        return database
    }

    override suspend fun databaseExists(location: DbLocation): Boolean = request("exists", location).exists == true

    override fun getDefaultLocation(): DbLocation = DbLocation("/$DEFAULT_DATABASE_NAME")

    override suspend fun backupDatabase(location: DbLocation): DbLocation {
        require(databaseExists(location)) { "Database does not exist at: $location" }
        val backup = DbLocation("${location.path}.backup")
        restore(backup, checkNotNull(request("read", location).bytes).toByteArray())
        deleteDatabase(location)
        return backup
    }

    override suspend fun deleteDatabase(location: DbLocation) {
        request("delete", location)
    }

    override suspend fun snapshot(database: MoneyManagerDatabaseWrapper): ByteArray {
        val driver = database.driver as WebSqliteDriver
        return checkNotNull(channel.send(sqliteRequest("snapshot", driver.name)).bytes).toByteArray()
    }

    override suspend fun databaseSizeBytes(location: DbLocation): Long? = request("size", location).size?.toLong()

    override suspend fun restore(
        location: DbLocation,
        bytes: ByteArray,
    ) {
        request("restore", location) { this.bytes = bytes.toInt8Array() }
    }

    private suspend fun request(
        type: String,
        location: DbLocation,
        configure: SqliteRequest.() -> Unit = {},
    ): SqliteResponse = channel.send(sqliteRequest(type, location.path, configure))
}
