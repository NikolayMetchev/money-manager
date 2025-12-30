@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database

import com.moneymanager.currency.Currency

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
     */
    private fun MoneyManagerDatabaseWrapper.createIncrementalRefreshTriggers() {
        // INSERT trigger - tracks both source and target account-currency pairs
        execute(
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
        execute(
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
        execute(
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
     */
    private fun MoneyManagerDatabaseWrapper.createCategoryDeleteTrigger() =
        execute(
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

    /**
     * Creates the temporary table used for batch import operations (CSV import).
     * When this table contains a row, attribute triggers are SKIPPED entirely (no audit).
     */
    private fun MoneyManagerDatabaseWrapper.createBatchImportTable() =
        execute(
            null,
            """
            CREATE TABLE IF NOT EXISTS _import_batch (active INTEGER)
            """.trimIndent(),
            0,
        )

    /**
     * Creates the temporary table used for initial transfer+attribute creation.
     * When this table contains a row, attribute triggers RECORD audit but DON'T bump revision.
     * This allows initial attributes to be recorded at revision 1 without incrementing.
     */
    private fun MoneyManagerDatabaseWrapper.createCreationModeTable() =
        execute(
            null,
            """
            CREATE TABLE IF NOT EXISTS _creation_mode (active INTEGER)
            """.trimIndent(),
            0,
        )

    /**
     * Creates triggers for transfer attribute auditing.
     *
     * Each attribute change (INSERT/UPDATE/DELETE) bumps the transfer revision
     * and records the change in TransferAttributeAudit.
     *
     * Storage pattern (same as Transfer_Audit):
     * - INSERT: stores NEW value (attribute that was added)
     * - UPDATE: stores OLD value (value before the change)
     * - DELETE: stores OLD value (value that was removed)
     *
     * Flag tables control trigger behavior:
     * - _import_batch: skip trigger entirely (CSV import - no audit)
     * - _creation_mode: record audit but don't bump revision (initial creation)
     * - neither: bump revision, then record audit (later modification)
     */
    private fun MoneyManagerDatabaseWrapper.createAttributeTriggers() {
        // Attribute INSERT trigger - records new attribute addition (stores NEW value)
        // The _import_batch guard skips this trigger during CSV import.
        // The _creation_mode guard prevents revision bump during initial creation.
        execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_attribute_insert_audit
            AFTER INSERT ON TransferAttribute
            FOR EACH ROW
            WHEN NOT EXISTS (SELECT 1 FROM _import_batch)
            BEGIN
                -- Only bump revision if NOT in creation mode
                UPDATE Transfer
                SET revisionId = revisionId + 1
                WHERE id = NEW.transactionId
                  AND NOT EXISTS (SELECT 1 FROM _creation_mode);

                -- Always record the addition in audit table at current revision
                INSERT INTO TransferAttributeAudit (transactionId, revisionId, attributeTypeId, auditTypeId, attributeValue)
                SELECT NEW.transactionId, revisionId, NEW.attributeTypeId, 1, NEW.attributeValue
                FROM Transfer WHERE id = NEW.transactionId;
            END
            """.trimIndent(),
            0,
        )

        // Attribute UPDATE trigger - records attribute value change (stores OLD value)
        execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_attribute_update_audit
            AFTER UPDATE ON TransferAttribute
            FOR EACH ROW
            WHEN NOT EXISTS (SELECT 1 FROM _import_batch)
              AND OLD.attributeValue != NEW.attributeValue
            BEGIN
                -- Only bump revision if NOT in creation mode
                UPDATE Transfer
                SET revisionId = revisionId + 1
                WHERE id = NEW.transactionId
                  AND NOT EXISTS (SELECT 1 FROM _creation_mode);

                -- Always record the change in audit table (OLD value - what it was before)
                INSERT INTO TransferAttributeAudit (transactionId, revisionId, attributeTypeId, auditTypeId, attributeValue)
                SELECT NEW.transactionId, revisionId, NEW.attributeTypeId, 2, OLD.attributeValue
                FROM Transfer WHERE id = NEW.transactionId;
            END
            """.trimIndent(),
            0,
        )

        // Attribute DELETE trigger - records attribute removal (stores OLD value)
        execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_attribute_delete_audit
            AFTER DELETE ON TransferAttribute
            FOR EACH ROW
            WHEN NOT EXISTS (SELECT 1 FROM _import_batch)
            BEGIN
                -- Only bump revision if NOT in creation mode
                UPDATE Transfer
                SET revisionId = revisionId + 1
                WHERE id = OLD.transactionId
                  AND NOT EXISTS (SELECT 1 FROM _creation_mode);

                -- Always record the deletion in audit table (OLD value - what was deleted)
                INSERT INTO TransferAttributeAudit (transactionId, revisionId, attributeTypeId, auditTypeId, attributeValue)
                SELECT OLD.transactionId, revisionId, OLD.attributeTypeId, 3, OLD.attributeValue
                FROM Transfer WHERE id = OLD.transactionId;
            END
            """.trimIndent(),
            0,
        )
    }

    /**
     * Creates audit triggers dynamically for all main tables.
     * Queries sqlite_master to discover tables and their columns, then generates triggers.
     *
     * Each table gets 3 triggers: INSERT, UPDATE, DELETE that record changes to audit tables.
     * - INSERT triggers store NEW values
     * - UPDATE triggers store OLD values (state before change)
     * - DELETE triggers store OLD values (state before deletion)
     *
     * NOTE: Triggers are created at runtime (not in schema) due to SQLDelight 2.2.1 parser limitations.
     * Called automatically from seedDatabase() during database initialization.
     */
    private fun MoneyManagerDatabaseWrapper.createAuditTriggers() {
        val tables = getAuditableTables()

        tables.forEach { tableName ->
            val auditTableName = "${tableName}_Audit"
            val columns = getTableColumns(tableName)

            val columnList = columns.joinToString(", ")
            val newColumnList = columns.joinToString(", ") { "NEW.$it" }
            val oldColumnList = columns.joinToString(", ") { "OLD.$it" }
            // For UPDATE: use OLD values for all columns EXCEPT revisionId, which uses NEW
            // (revisionId is incremented during UPDATE, so OLD.revisionId is pre-increment)
            val updateColumnList =
                columns.joinToString(", ") { col ->
                    if (col == "revisionId") "NEW.$col" else "OLD.$col"
                }

            // INSERT trigger - stores NEW values with auditTypeId 1
            // Uses strftime('%s', 'now') for cross-platform compatibility (works on all SQLite versions)
            execute(
                null,
                """
                CREATE TRIGGER IF NOT EXISTS trigger_${tableName.lowercase()}_insert_audit
                AFTER INSERT ON $tableName
                FOR EACH ROW
                BEGIN
                    INSERT INTO $auditTableName (auditTimestamp, auditTypeId, $columnList)
                    VALUES (CAST(strftime('%s', 'now') AS INTEGER) * 1000, 1, $newColumnList);
                END
                """.trimIndent(),
                0,
            )

            // UPDATE trigger - stores OLD values (except revisionId uses NEW) with auditTypeId 2
            execute(
                null,
                """
                CREATE TRIGGER IF NOT EXISTS trigger_${tableName.lowercase()}_update_audit
                AFTER UPDATE ON $tableName
                FOR EACH ROW
                BEGIN
                    INSERT INTO $auditTableName (auditTimestamp, auditTypeId, $columnList)
                    VALUES (CAST(strftime('%s', 'now') AS INTEGER) * 1000, 2, $updateColumnList);
                END
                """.trimIndent(),
                0,
            )

            // DELETE trigger - stores OLD values with auditTypeId 3
            execute(
                null,
                """
                CREATE TRIGGER IF NOT EXISTS trigger_${tableName.lowercase()}_delete_audit
                AFTER DELETE ON $tableName
                FOR EACH ROW
                BEGIN
                    INSERT INTO $auditTableName (auditTimestamp, auditTypeId, $columnList)
                    VALUES (CAST(strftime('%s', 'now') AS INTEGER) * 1000, 3, $oldColumnList);
                END
                """.trimIndent(),
                0,
            )
        }
    }

    /**
     * Seeds the database with all available currencies and creates incremental refresh triggers.
     * Should be called once after creating a new database.
     *
     * @param database The database to seed
     */
    suspend fun seedDatabase(database: MoneyManagerDatabaseWrapper) {
        with(database) {
            // Seed AuditType lookup table
            auditTypeQueries.insert(id = 1, name = "INSERT")
            auditTypeQueries.insert(id = 2, name = "UPDATE")
            auditTypeQueries.insert(id = 3, name = "DELETE")

            // Seed SourceType lookup table
            sourceTypeQueries.insert(id = 1, name = "MANUAL")
            sourceTypeQueries.insert(id = 2, name = "CSV_IMPORT")
            sourceTypeQueries.insert(id = 3, name = "SAMPLE_GENERATOR")

            // Seed Platform lookup table
            platformQueries.insert(id = 1, name = "JVM")
            platformQueries.insert(id = 2, name = "ANDROID")

            // Create triggers for incremental materialized view refresh
            createIncrementalRefreshTriggers()

            // Create trigger for category deletion (children inherit grandparent)
            createCategoryDeleteTrigger()

            // Create flag tables and attribute triggers
            createBatchImportTable()
            createCreationModeTable()
            createAttributeTriggers()

            // Create audit triggers for all main tables
            createAuditTriggers()

            // Seed default "Uncategorized" category
            categoryQueries.insertWithId(
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
}
