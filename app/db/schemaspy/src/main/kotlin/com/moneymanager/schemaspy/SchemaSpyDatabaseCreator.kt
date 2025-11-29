package com.moneymanager.schemaspy

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.moneymanager.database.sql.MoneyManagerDatabase

/**
 * Helper tool to create a physical SQLite database file for SchemaSpy documentation generation.
 * This creates the database schema without any data.
 */
object SchemaSpyDatabaseCreator {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("Usage: SchemaSpyDatabaseCreator <database-file-path>")
            System.exit(1)
        }

        val dbPath = args[0]
        println("Creating database at: $dbPath")

        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
        MoneyManagerDatabase.Schema.create(driver)
        driver.close()

        println("Database created successfully!")
    }
}
