@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database

import com.moneymanager.currency.Currency
import com.moneymanager.domain.repository.CurrencyRepository

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
            AFTER INSERT ON transfer
            FOR EACH ROW
            BEGIN
                INSERT OR IGNORE INTO pending_materialized_view_changes (account_id, currency_id, min_timestamp)
                VALUES (NEW.source_account_id, NEW.currency_id, NEW.timestamp);

                UPDATE pending_materialized_view_changes
                SET min_timestamp = NEW.timestamp
                WHERE account_id = NEW.source_account_id
                  AND currency_id = NEW.currency_id
                  AND min_timestamp > NEW.timestamp;

                INSERT OR IGNORE INTO pending_materialized_view_changes (account_id, currency_id, min_timestamp)
                VALUES (NEW.target_account_id, NEW.currency_id, NEW.timestamp);

                UPDATE pending_materialized_view_changes
                SET min_timestamp = NEW.timestamp
                WHERE account_id = NEW.target_account_id
                  AND currency_id = NEW.currency_id
                  AND min_timestamp > NEW.timestamp;
            END
            """.trimIndent(),
            0,
        )

        // UPDATE trigger - tracks all 4 possible account-currency pairs (old/new source/target)
        execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_transfer_update_track_changes
            AFTER UPDATE ON transfer
            FOR EACH ROW
            BEGIN
                INSERT OR IGNORE INTO pending_materialized_view_changes (account_id, currency_id, min_timestamp)
                VALUES (OLD.source_account_id, OLD.currency_id, MIN(OLD.timestamp, NEW.timestamp));

                UPDATE pending_materialized_view_changes
                SET min_timestamp = MIN(OLD.timestamp, NEW.timestamp)
                WHERE account_id = OLD.source_account_id
                  AND currency_id = OLD.currency_id
                  AND min_timestamp > MIN(OLD.timestamp, NEW.timestamp);

                INSERT OR IGNORE INTO pending_materialized_view_changes (account_id, currency_id, min_timestamp)
                VALUES (NEW.source_account_id, NEW.currency_id, MIN(OLD.timestamp, NEW.timestamp));

                UPDATE pending_materialized_view_changes
                SET min_timestamp = MIN(OLD.timestamp, NEW.timestamp)
                WHERE account_id = NEW.source_account_id
                  AND currency_id = NEW.currency_id
                  AND min_timestamp > MIN(OLD.timestamp, NEW.timestamp);

                INSERT OR IGNORE INTO pending_materialized_view_changes (account_id, currency_id, min_timestamp)
                VALUES (OLD.target_account_id, OLD.currency_id, MIN(OLD.timestamp, NEW.timestamp));

                UPDATE pending_materialized_view_changes
                SET min_timestamp = MIN(OLD.timestamp, NEW.timestamp)
                WHERE account_id = OLD.target_account_id
                  AND currency_id = OLD.currency_id
                  AND min_timestamp > MIN(OLD.timestamp, NEW.timestamp);

                INSERT OR IGNORE INTO pending_materialized_view_changes (account_id, currency_id, min_timestamp)
                VALUES (NEW.target_account_id, NEW.currency_id, MIN(OLD.timestamp, NEW.timestamp));

                UPDATE pending_materialized_view_changes
                SET min_timestamp = MIN(OLD.timestamp, NEW.timestamp)
                WHERE account_id = NEW.target_account_id
                  AND currency_id = NEW.currency_id
                  AND min_timestamp > MIN(OLD.timestamp, NEW.timestamp);
            END
            """.trimIndent(),
            0,
        )

        // DELETE trigger - tracks old source and target account-currency pairs
        execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_transfer_delete_track_changes
            AFTER DELETE ON transfer
            FOR EACH ROW
            BEGIN
                INSERT OR IGNORE INTO pending_materialized_view_changes (account_id, currency_id, min_timestamp)
                VALUES (OLD.source_account_id, OLD.currency_id, OLD.timestamp);

                UPDATE pending_materialized_view_changes
                SET min_timestamp = OLD.timestamp
                WHERE account_id = OLD.source_account_id
                  AND currency_id = OLD.currency_id
                  AND min_timestamp > OLD.timestamp;

                INSERT OR IGNORE INTO pending_materialized_view_changes (account_id, currency_id, min_timestamp)
                VALUES (OLD.target_account_id, OLD.currency_id, OLD.timestamp);

                UPDATE pending_materialized_view_changes
                SET min_timestamp = OLD.timestamp
                WHERE account_id = OLD.target_account_id
                  AND currency_id = OLD.currency_id
                  AND min_timestamp > OLD.timestamp;
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
            BEFORE DELETE ON category
            FOR EACH ROW
            BEGIN
                UPDATE category
                SET parent_id = OLD.parent_id
                WHERE parent_id = OLD.id;
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
     * Creates custom audit triggers for the category table.
     * Unlike the auto-generated triggers, these include a subquery to capture the
     * parent category name at audit time (denormalized into the audit row).
     *
     * Must be called BEFORE createAuditTriggers() so the generic IF NOT EXISTS triggers
     * are skipped for category.
     */
    private fun MoneyManagerDatabaseWrapper.createCategoryAuditTriggers() {
        // INSERT trigger - stores NEW values, looks up parent name
        execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_category_insert_audit
            AFTER INSERT ON category
            FOR EACH ROW
            BEGIN
                INSERT INTO category_audit (audit_timestamp, audit_type_id, category_id, revision_id, name, parent_id, parent_name)
                VALUES (
                    CAST(strftime('%s', 'now') AS INTEGER) * 1000,
                    1,
                    NEW.id,
                    NEW.revision_id,
                    NEW.name,
                    NEW.parent_id,
                    (SELECT name FROM category WHERE id = NEW.parent_id)
                );
            END
            """.trimIndent(),
            0,
        )

        // UPDATE trigger - stores OLD values (except revision_id uses NEW), looks up old parent name
        execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_category_update_audit
            AFTER UPDATE ON category
            FOR EACH ROW
            BEGIN
                INSERT INTO category_audit (audit_timestamp, audit_type_id, category_id, revision_id, name, parent_id, parent_name)
                VALUES (
                    CAST(strftime('%s', 'now') AS INTEGER) * 1000,
                    2,
                    OLD.id,
                    NEW.revision_id,
                    OLD.name,
                    OLD.parent_id,
                    (SELECT name FROM category WHERE id = OLD.parent_id)
                );
            END
            """.trimIndent(),
            0,
        )

        // DELETE trigger - stores OLD values, looks up old parent name
        execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_category_delete_audit
            AFTER DELETE ON category
            FOR EACH ROW
            BEGIN
                INSERT INTO category_audit (audit_timestamp, audit_type_id, category_id, revision_id, name, parent_id, parent_name)
                VALUES (
                    CAST(strftime('%s', 'now') AS INTEGER) * 1000,
                    3,
                    OLD.id,
                    OLD.revision_id,
                    OLD.name,
                    OLD.parent_id,
                    (SELECT name FROM category WHERE id = OLD.parent_id)
                );
            END
            """.trimIndent(),
            0,
        )
    }

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
            AFTER INSERT ON transfer_attribute
            FOR EACH ROW
            WHEN NOT EXISTS (SELECT 1 FROM _import_batch)
            BEGIN
                -- Only bump revision if NOT in creation mode
                UPDATE transfer
                SET revision_id = revision_id + 1
                WHERE id = NEW.transaction_id
                  AND NOT EXISTS (SELECT 1 FROM _creation_mode);

                -- Always record the addition in audit table at current revision
                INSERT INTO transfer_attribute_audit (audit_timestamp, audit_type_id, transfer_id, revision_id, attribute_type_id, attribute_value)
                SELECT CAST(strftime('%s', 'now') AS INTEGER) * 1000, 1, NEW.transaction_id, revision_id, NEW.attribute_type_id, NEW.attribute_value
                FROM transfer WHERE id = NEW.transaction_id;
            END
            """.trimIndent(),
            0,
        )

        // Attribute UPDATE trigger - records attribute value change (stores OLD value)
        execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_attribute_update_audit
            AFTER UPDATE ON transfer_attribute
            FOR EACH ROW
            WHEN NOT EXISTS (SELECT 1 FROM _import_batch)
              AND OLD.attribute_value != NEW.attribute_value
            BEGIN
                -- Only bump revision if NOT in creation mode
                UPDATE transfer
                SET revision_id = revision_id + 1
                WHERE id = NEW.transaction_id
                  AND NOT EXISTS (SELECT 1 FROM _creation_mode);

                -- Always record the change in audit table (OLD value - what it was before)
                INSERT INTO transfer_attribute_audit (audit_timestamp, audit_type_id, transfer_id, revision_id, attribute_type_id, attribute_value)
                SELECT CAST(strftime('%s', 'now') AS INTEGER) * 1000, 2, NEW.transaction_id, revision_id, NEW.attribute_type_id, OLD.attribute_value
                FROM transfer WHERE id = NEW.transaction_id;
            END
            """.trimIndent(),
            0,
        )

        // Attribute DELETE trigger - records attribute removal (stores OLD value)
        execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_attribute_delete_audit
            AFTER DELETE ON transfer_attribute
            FOR EACH ROW
            WHEN NOT EXISTS (SELECT 1 FROM _import_batch)
            BEGIN
                -- Only bump revision if NOT in creation mode
                UPDATE transfer
                SET revision_id = revision_id + 1
                WHERE id = OLD.transaction_id
                  AND NOT EXISTS (SELECT 1 FROM _creation_mode);

                -- Always record the deletion in audit table (OLD value - what was deleted)
                INSERT INTO transfer_attribute_audit (audit_timestamp, audit_type_id, transfer_id, revision_id, attribute_type_id, attribute_value)
                SELECT CAST(strftime('%s', 'now') AS INTEGER) * 1000, 3, OLD.transaction_id, revision_id, OLD.attribute_type_id, OLD.attribute_value
                FROM transfer WHERE id = OLD.transaction_id;
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
            val auditTableName = "${tableName}_audit"
            val columns = getTableColumns(tableName)
            val auditEntityIdCol = "${tableName}_id"

            // Map main table column names to audit table column names (id -> entity_id)
            val auditColumnList =
                columns.joinToString(", ") { col ->
                    if (col == "id") auditEntityIdCol else col
                }
            val newColumnList = columns.joinToString(", ") { "NEW.$it" }
            val oldColumnList = columns.joinToString(", ") { "OLD.$it" }
            // For UPDATE: use OLD values for all columns EXCEPT revision_id, which uses NEW
            // (revision_id is incremented during UPDATE, so OLD.revision_id is pre-increment)
            val updateColumnList =
                columns.joinToString(", ") { col ->
                    if (col == "revision_id") "NEW.$col" else "OLD.$col"
                }

            // INSERT trigger - stores NEW values with audit_type_id 1
            // Uses strftime('%s', 'now') for cross-platform compatibility (works on all SQLite versions)
            execute(
                null,
                """
                CREATE TRIGGER IF NOT EXISTS trigger_${tableName.lowercase()}_insert_audit
                AFTER INSERT ON $tableName
                FOR EACH ROW
                BEGIN
                    INSERT INTO $auditTableName (audit_timestamp, audit_type_id, $auditColumnList)
                    VALUES (CAST(strftime('%s', 'now') AS INTEGER) * 1000, 1, $newColumnList);
                END
                """.trimIndent(),
                0,
            )

            // UPDATE trigger - stores OLD values (except revision_id uses NEW) with audit_type_id 2
            execute(
                null,
                """
                CREATE TRIGGER IF NOT EXISTS trigger_${tableName.lowercase()}_update_audit
                AFTER UPDATE ON $tableName
                FOR EACH ROW
                BEGIN
                    INSERT INTO $auditTableName (audit_timestamp, audit_type_id, $auditColumnList)
                    VALUES (CAST(strftime('%s', 'now') AS INTEGER) * 1000, 2, $updateColumnList);
                END
                """.trimIndent(),
                0,
            )

            // DELETE trigger - stores OLD values with audit_type_id 3
            execute(
                null,
                """
                CREATE TRIGGER IF NOT EXISTS trigger_${tableName.lowercase()}_delete_audit
                AFTER DELETE ON $tableName
                FOR EACH ROW
                BEGIN
                    INSERT INTO $auditTableName (audit_timestamp, audit_type_id, $auditColumnList)
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
    suspend fun seedDatabase(
        database: MoneyManagerDatabaseWrapper,
        currencyRepository: CurrencyRepository,
    ) {
        with(database) {
            // Seed AuditType lookup table
            auditTypeQueries.insert(id = 1, name = "INSERT")
            auditTypeQueries.insert(id = 2, name = "UPDATE")
            auditTypeQueries.insert(id = 3, name = "DELETE")

            // Seed SourceType lookup table
            sourceTypeQueries.insert(id = 1, name = "MANUAL")
            sourceTypeQueries.insert(id = 2, name = "CSV_IMPORT")
            sourceTypeQueries.insert(id = 3, name = "SAMPLE_GENERATOR")
            sourceTypeQueries.insert(id = 4, name = "SYSTEM")

            // Seed Platform lookup table
            platformQueries.insert(id = 0, name = "SYSTEM")
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

            // Create custom category audit triggers (must be before generic ones)
            // These include parent_name denormalization via subquery
            createCategoryAuditTriggers()

            // Create audit triggers for all main tables
            // Category triggers are skipped because custom ones already exist (IF NOT EXISTS)
            createAuditTriggers()

            // Seed default "Uncategorized" category
            categoryQueries.insertWithId(
                id = -1,
                name = "Uncategorized",
                parent_id = null,
            )

            // Create system device for system-generated source tracking
            // Platform 0 = SYSTEM, no os/machine/make/model needed
            deviceQueries.insertSystemDevice(platform_id = 0)
            val systemDeviceId =
                deviceQueries.selectSystemDevice(platform_id = 0).executeAsOneOrNull()
                    ?: deviceQueries.lastInsertRowId().executeAsOne()

            // Seed currencies with source tracking (entity_type_id=3 CURRENCY, source_type_id=4 SYSTEM)
            allCurrencies.forEach { currency ->
                val currencyId = currencyRepository.upsertCurrencyByCode(currency.code, currency.displayName)
                entitySourceQueries.insertSource(
                    entity_type_id = 3,
                    entity_id = currencyId.id,
                    revision_id = 1,
                    source_type_id = 4,
                    device_id = systemDeviceId,
                )
            }
        }
    }
}
