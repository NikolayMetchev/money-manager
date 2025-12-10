@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database

import app.cash.sqldelight.db.SqlDriver
import com.moneymanager.currency.Currency
import com.moneymanager.database.sql.MoneyManagerDatabase

/**
 * Centralized database configuration for SQLite PRAGMA settings and seed data.
 * These settings are applied per-connection (not persisted to the database file).
 */
object DatabaseConfig {
    /**
     * SQL statements to execute when opening a database connection.
     * Applied to all database connections (JVM, Android, etc.)
     */
    val connectionPragmas =
        listOf(
            // Enable foreign key constraints (disabled by default in SQLite)
            "PRAGMA foreign_keys = ON",
        )

    /**
     * All available ISO 4217 currencies from the platform.
     */
    val allCurrencies: List<Currency>
        get() = Currency.getAllCurrencies()

    /**
     * Creates triggers for incremental materialized view refresh.
     * These triggers track changes to the Transfer table in PendingMaterializedViewChanges.
     *
     * NOTE: Triggers are created at runtime (not in schema) due to SQLDelight 2.2.1 parser limitations.
     * Called automatically from seedDatabase() during database initialization.
     *
     * @param driver The SQLite driver to use for executing the CREATE TRIGGER statements
     */
    private fun createIncrementalRefreshTriggers(driver: SqlDriver) {
        // INSERT trigger - tracks both source and target account-currency pairs
        driver.execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_transfer_insert_track_changes
            AFTER INSERT ON Transfer
            FOR EACH ROW
            BEGIN
                INSERT OR IGNORE INTO PendingMaterializedViewChanges (accountId, currencyId, minTimestamp)
                VALUES (NEW.sourceAccountId, NEW.currencyId, NEW.timestamp);

                UPDATE PendingMaterializedViewChanges
                SET minTimestamp = NEW.timestamp
                WHERE accountId = NEW.sourceAccountId
                  AND currencyId = NEW.currencyId
                  AND minTimestamp > NEW.timestamp;

                INSERT OR IGNORE INTO PendingMaterializedViewChanges (accountId, currencyId, minTimestamp)
                VALUES (NEW.targetAccountId, NEW.currencyId, NEW.timestamp);

                UPDATE PendingMaterializedViewChanges
                SET minTimestamp = NEW.timestamp
                WHERE accountId = NEW.targetAccountId
                  AND currencyId = NEW.currencyId
                  AND minTimestamp > NEW.timestamp;
            END
            """.trimIndent(),
            0,
        )

        // UPDATE trigger - tracks all 4 possible account-currency pairs (old/new source/target)
        driver.execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_transfer_update_track_changes
            AFTER UPDATE ON Transfer
            FOR EACH ROW
            BEGIN
                INSERT OR IGNORE INTO PendingMaterializedViewChanges (accountId, currencyId, minTimestamp)
                VALUES (OLD.sourceAccountId, OLD.currencyId, MIN(OLD.timestamp, NEW.timestamp));

                UPDATE PendingMaterializedViewChanges
                SET minTimestamp = MIN(OLD.timestamp, NEW.timestamp)
                WHERE accountId = OLD.sourceAccountId
                  AND currencyId = OLD.currencyId
                  AND minTimestamp > MIN(OLD.timestamp, NEW.timestamp);

                INSERT OR IGNORE INTO PendingMaterializedViewChanges (accountId, currencyId, minTimestamp)
                VALUES (NEW.sourceAccountId, NEW.currencyId, MIN(OLD.timestamp, NEW.timestamp));

                UPDATE PendingMaterializedViewChanges
                SET minTimestamp = MIN(OLD.timestamp, NEW.timestamp)
                WHERE accountId = NEW.sourceAccountId
                  AND currencyId = NEW.currencyId
                  AND minTimestamp > MIN(OLD.timestamp, NEW.timestamp);

                INSERT OR IGNORE INTO PendingMaterializedViewChanges (accountId, currencyId, minTimestamp)
                VALUES (OLD.targetAccountId, OLD.currencyId, MIN(OLD.timestamp, NEW.timestamp));

                UPDATE PendingMaterializedViewChanges
                SET minTimestamp = MIN(OLD.timestamp, NEW.timestamp)
                WHERE accountId = OLD.targetAccountId
                  AND currencyId = OLD.currencyId
                  AND minTimestamp > MIN(OLD.timestamp, NEW.timestamp);

                INSERT OR IGNORE INTO PendingMaterializedViewChanges (accountId, currencyId, minTimestamp)
                VALUES (NEW.targetAccountId, NEW.currencyId, MIN(OLD.timestamp, NEW.timestamp));

                UPDATE PendingMaterializedViewChanges
                SET minTimestamp = MIN(OLD.timestamp, NEW.timestamp)
                WHERE accountId = NEW.targetAccountId
                  AND currencyId = NEW.currencyId
                  AND minTimestamp > MIN(OLD.timestamp, NEW.timestamp);
            END
            """.trimIndent(),
            0,
        )

        // DELETE trigger - tracks old source and target account-currency pairs
        driver.execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_transfer_delete_track_changes
            AFTER DELETE ON Transfer
            FOR EACH ROW
            BEGIN
                INSERT OR IGNORE INTO PendingMaterializedViewChanges (accountId, currencyId, minTimestamp)
                VALUES (OLD.sourceAccountId, OLD.currencyId, OLD.timestamp);

                UPDATE PendingMaterializedViewChanges
                SET minTimestamp = OLD.timestamp
                WHERE accountId = OLD.sourceAccountId
                  AND currencyId = OLD.currencyId
                  AND minTimestamp > OLD.timestamp;

                INSERT OR IGNORE INTO PendingMaterializedViewChanges (accountId, currencyId, minTimestamp)
                VALUES (OLD.targetAccountId, OLD.currencyId, OLD.timestamp);

                UPDATE PendingMaterializedViewChanges
                SET minTimestamp = OLD.timestamp
                WHERE accountId = OLD.targetAccountId
                  AND currencyId = OLD.currencyId
                  AND minTimestamp > OLD.timestamp;
            END
            """.trimIndent(),
            0,
        )
    }

    /**
     * Creates a trigger to update children's parentId when a category is deleted.
     * Children inherit the deleted category's parent (grandparent becomes parent).
     *
     * @param driver The SQLite driver to use for executing the CREATE TRIGGER statement
     */
    private fun createCategoryDeleteTrigger(driver: SqlDriver) {
        driver.execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_category_delete_update_children
            BEFORE DELETE ON Category
            FOR EACH ROW
            BEGIN
                UPDATE Category
                SET parentId = OLD.parentId
                WHERE parentId = OLD.id;
            END
            """.trimIndent(),
            0,
        )
    }

    /**
     * Seeds the database with all available currencies and creates incremental refresh triggers.
     * Should be called once after creating a new database.
     *
     * @param database The database to seed
     * @param driver The SQLite driver (needed for creating triggers)
     */
    suspend fun seedDatabase(
        database: MoneyManagerDatabase,
        driver: SqlDriver,
    ) {
        // Create triggers for incremental materialized view refresh
        createIncrementalRefreshTriggers(driver)

        // Create trigger for category deletion (children inherit grandparent)
        createCategoryDeleteTrigger(driver)

        // Seed default "Uncategorized" category
        database.categoryQueries.insertWithId(
            id = -1,
            name = "Uncategorized",
            parentId = null,
        )

        // Seed currencies
        val currencyRepository = RepositorySet(database).currencyRepository
        allCurrencies.forEach { currency ->
            currencyRepository.upsertCurrencyByCode(currency.code, currency.displayName)
        }
    }
}
