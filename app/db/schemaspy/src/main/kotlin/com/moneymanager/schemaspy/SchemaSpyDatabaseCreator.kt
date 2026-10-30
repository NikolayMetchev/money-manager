package com.moneymanager.schemaspy

import com.moneymanager.database.JvmDatabaseManager
import com.moneymanager.domain.model.DbLocation
import kotlin.system.exitProcess

/**
 * Helper tool to create a physical SQLite database file for SchemaSpy documentation generation.
 * This creates the database schema without any data.
 */
suspend fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: SchemaSpyDatabaseCreator <database-file-path>")
        exitProcess(1)
    }

    val dbPath = args[0]
    println("Creating database at: $dbPath")

    val databaseManager = JvmDatabaseManager()
    val location = DbLocation(dbPath)
    // The database is in WAL mode: closing it checkpoints the schema into the file SchemaSpy reads, as the
    // -wal sidecar isn't part of the output.
    databaseManager.openDatabase(location).close()

    println("Database created successfully!")
}
