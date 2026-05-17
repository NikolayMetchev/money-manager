package com.moneymanager.schemaspy

import com.moneymanager.database.JvmDatabaseManager
import com.moneymanager.domain.model.DbLocation
import kotlin.io.path.Path
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
    val location = DbLocation(path = Path(dbPath))
    databaseManager.openDatabase(location)

    println("Database created successfully!")
}
