@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database

import com.moneymanager.currency.Currency
import com.moneymanager.database.json.ApiStrategyConfigJson
import com.moneymanager.database.json.ApiStrategyJsonCodec
import com.moneymanager.database.json.FieldMappingJsonCodec
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.SourceType
import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiAuthType
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiSignSource
import com.moneymanager.domain.model.apistrategy.ApiSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.BuiltInCounterpartyRule
import com.moneymanager.domain.model.apistrategy.PaginationMode
import com.moneymanager.domain.model.apistrategy.PredicateOp
import com.moneymanager.domain.model.apistrategy.RulePredicate
import com.moneymanager.domain.model.apistrategy.RuleSign
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.AttributeColumnMapping
import com.moneymanager.domain.model.csvstrategy.ColumnPairSwap
import com.moneymanager.domain.model.csvstrategy.CompanionTransactionRule
import com.moneymanager.domain.model.csvstrategy.ConditionalAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.RowCondition
import com.moneymanager.domain.model.csvstrategy.RowConditionOperator
import com.moneymanager.domain.model.csvstrategy.RowPreprocessingRule
import com.moneymanager.domain.model.csvstrategy.TemplateAccountMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.repository.CurrencyRepository
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Centralized database configuration for SQLite PRAGMA settings and seed data.
 * These settings are applied per-connection (not persisted to the database file).
 */
object DatabaseConfig {
    /** Stable ID for the "excluded" attribute type. Excluded transactions are hidden from balances. */
    const val EXCLUDED_ATTR_TYPE_ID: Long = -1

    /** Stable ID for the "account-external-id" attribute type (e.g. counterparty.id value). */
    const val ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID: Long = -2

    /** Stable ID for the "built-in type" attribute type used by built-in counterparty accounts. */
    const val BUILT_IN_COUNTERPARTY_TYPE_ATTR_TYPE_ID: Long = -3

    /** Stable ID for the "sort code" account attribute type used for personal counterparties. */
    const val ACCOUNT_SORT_CODE_ATTR_TYPE_ID: Long = -5

    /** Stable ID for the "account number" account attribute type used for personal counterparties. */
    const val ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID: Long = -6

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
     * Creates triggers to schedule a materialized view refresh when the "excluded" attribute
     * (attribute_type_id = -1) is added to or removed from a transfer.
     *
     * These fire in addition to the general attribute audit triggers and are responsible only
     * for updating pending_materialized_view_changes so that balance and running-balance
     * materialized views are refreshed from the affected timestamp onward.
     *
     * NOTE: Triggers are created at runtime (not in schema) due to SQLDelight 2.2.1 parser limitations.
     * Called automatically from seedDatabase() during database initialization.
     */
    private fun MoneyManagerDatabaseWrapper.createExcludedAttributeTriggers() {
        // INSERT trigger - when a transaction is marked excluded, schedule balance refresh
        execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_excluded_attribute_insert_track_changes
            AFTER INSERT ON transfer_attribute
            FOR EACH ROW
            WHEN NEW.attribute_type_id = -1
            BEGIN
                INSERT OR IGNORE INTO pending_materialized_view_changes (account_id, currency_id, min_timestamp)
                SELECT source_account_id, currency_id, timestamp FROM transfer WHERE id = NEW.transaction_id;

                UPDATE pending_materialized_view_changes
                SET min_timestamp = (SELECT timestamp FROM transfer WHERE id = NEW.transaction_id)
                WHERE account_id = (SELECT source_account_id FROM transfer WHERE id = NEW.transaction_id)
                  AND currency_id = (SELECT currency_id FROM transfer WHERE id = NEW.transaction_id)
                  AND min_timestamp > (SELECT timestamp FROM transfer WHERE id = NEW.transaction_id);

                INSERT OR IGNORE INTO pending_materialized_view_changes (account_id, currency_id, min_timestamp)
                SELECT target_account_id, currency_id, timestamp FROM transfer WHERE id = NEW.transaction_id;

                UPDATE pending_materialized_view_changes
                SET min_timestamp = (SELECT timestamp FROM transfer WHERE id = NEW.transaction_id)
                WHERE account_id = (SELECT target_account_id FROM transfer WHERE id = NEW.transaction_id)
                  AND currency_id = (SELECT currency_id FROM transfer WHERE id = NEW.transaction_id)
                  AND min_timestamp > (SELECT timestamp FROM transfer WHERE id = NEW.transaction_id);
            END
            """.trimIndent(),
            0,
        )

        // DELETE trigger - when a transaction is un-excluded, schedule balance refresh
        execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_excluded_attribute_delete_track_changes
            AFTER DELETE ON transfer_attribute
            FOR EACH ROW
            WHEN OLD.attribute_type_id = -1
            BEGIN
                INSERT OR IGNORE INTO pending_materialized_view_changes (account_id, currency_id, min_timestamp)
                SELECT source_account_id, currency_id, timestamp FROM transfer WHERE id = OLD.transaction_id;

                UPDATE pending_materialized_view_changes
                SET min_timestamp = (SELECT timestamp FROM transfer WHERE id = OLD.transaction_id)
                WHERE account_id = (SELECT source_account_id FROM transfer WHERE id = OLD.transaction_id)
                  AND currency_id = (SELECT currency_id FROM transfer WHERE id = OLD.transaction_id)
                  AND min_timestamp > (SELECT timestamp FROM transfer WHERE id = OLD.transaction_id);

                INSERT OR IGNORE INTO pending_materialized_view_changes (account_id, currency_id, min_timestamp)
                SELECT target_account_id, currency_id, timestamp FROM transfer WHERE id = OLD.transaction_id;

                UPDATE pending_materialized_view_changes
                SET min_timestamp = (SELECT timestamp FROM transfer WHERE id = OLD.transaction_id)
                WHERE account_id = (SELECT target_account_id FROM transfer WHERE id = OLD.transaction_id)
                  AND currency_id = (SELECT currency_id FROM transfer WHERE id = OLD.transaction_id)
                  AND min_timestamp > (SELECT timestamp FROM transfer WHERE id = OLD.transaction_id);
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
     * Creates triggers for account attribute auditing.
     *
     * Each attribute change (INSERT/UPDATE/DELETE) bumps the account revision
     * and records the change in AccountAttributeAudit.
     *
     * Storage pattern (same as Transfer_Attribute_Audit):
     * - INSERT: stores NEW value (attribute that was added)
     * - UPDATE: stores OLD value (value before the change)
     * - DELETE: stores OLD value (value that was removed)
     *
     * Flag tables control trigger behavior:
     * - _import_batch: skip trigger entirely (CSV import - no audit)
     * - _creation_mode: record audit but don't bump revision (initial creation)
     * - neither: bump revision, then record audit (later modification)
     */
    private fun MoneyManagerDatabaseWrapper.createAccountAttributeTriggers() {
        // Attribute INSERT trigger - records new attribute addition (stores NEW value)
        execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_account_attribute_insert_audit
            AFTER INSERT ON account_attribute
            FOR EACH ROW
            WHEN NOT EXISTS (SELECT 1 FROM _import_batch)
            BEGIN
                -- Only bump revision if NOT in creation mode
                UPDATE account
                SET revision_id = revision_id + 1
                WHERE id = NEW.account_id
                  AND NOT EXISTS (SELECT 1 FROM _creation_mode);

                -- Always record the addition in audit table at current revision
                INSERT INTO account_attribute_audit (audit_timestamp, audit_type_id, account_id, revision_id, attribute_type_id, attribute_value)
                SELECT CAST(strftime('%s', 'now') AS INTEGER) * 1000, 1, NEW.account_id, revision_id, NEW.attribute_type_id, NEW.attribute_value
                FROM account WHERE id = NEW.account_id;
            END
            """.trimIndent(),
            0,
        )

        // Attribute UPDATE trigger - records attribute value change (stores OLD value)
        execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_account_attribute_update_audit
            AFTER UPDATE ON account_attribute
            FOR EACH ROW
            WHEN NOT EXISTS (SELECT 1 FROM _import_batch)
              AND OLD.attribute_value != NEW.attribute_value
            BEGIN
                -- Only bump revision if NOT in creation mode
                UPDATE account
                SET revision_id = revision_id + 1
                WHERE id = NEW.account_id
                  AND NOT EXISTS (SELECT 1 FROM _creation_mode);

                -- Always record the change in audit table (OLD value - what it was before)
                INSERT INTO account_attribute_audit (audit_timestamp, audit_type_id, account_id, revision_id, attribute_type_id, attribute_value)
                SELECT CAST(strftime('%s', 'now') AS INTEGER) * 1000, 2, NEW.account_id, revision_id, NEW.attribute_type_id, OLD.attribute_value
                FROM account WHERE id = NEW.account_id;
            END
            """.trimIndent(),
            0,
        )

        // Attribute DELETE trigger - records attribute removal (stores OLD value)
        execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_account_attribute_delete_audit
            AFTER DELETE ON account_attribute
            FOR EACH ROW
            WHEN NOT EXISTS (SELECT 1 FROM _import_batch)
            BEGIN
                -- Only bump revision if NOT in creation mode
                UPDATE account
                SET revision_id = revision_id + 1
                WHERE id = OLD.account_id
                  AND NOT EXISTS (SELECT 1 FROM _creation_mode);

                -- Always record the deletion in audit table (OLD value - what was deleted)
                INSERT INTO account_attribute_audit (audit_timestamp, audit_type_id, account_id, revision_id, attribute_type_id, attribute_value)
                SELECT CAST(strftime('%s', 'now') AS INTEGER) * 1000, 3, OLD.account_id, revision_id, OLD.attribute_type_id, OLD.attribute_value
                FROM account WHERE id = OLD.account_id;
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
     * Creates audit triggers for person attributes.
     * Each attribute change bumps the person revision and records to person_attribute_audit.
     * Must be called BEFORE createAuditTriggers() so generic triggers are skipped via IF NOT EXISTS.
     */
    private fun MoneyManagerDatabaseWrapper.createPersonAttributeTriggers() {
        execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_person_attribute_insert_audit
            AFTER INSERT ON person_attribute
            FOR EACH ROW
            BEGIN
                UPDATE person
                SET revision_id = revision_id + 1
                WHERE id = NEW.person_id
                  AND NOT EXISTS (SELECT 1 FROM _creation_mode);

                INSERT INTO person_attribute_audit (audit_timestamp, audit_type_id, person_id, revision_id, attribute_type_id, attribute_value)
                SELECT CAST(strftime('%s', 'now') AS INTEGER) * 1000, 1, NEW.person_id, revision_id, NEW.attribute_type_id, NEW.attribute_value
                FROM person WHERE id = NEW.person_id;
            END
            """.trimIndent(),
            0,
        )

        execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_person_attribute_update_audit
            AFTER UPDATE ON person_attribute
            FOR EACH ROW
            WHEN OLD.attribute_value != NEW.attribute_value
            BEGIN
                UPDATE person
                SET revision_id = revision_id + 1
                WHERE id = NEW.person_id
                  AND NOT EXISTS (SELECT 1 FROM _creation_mode);

                INSERT INTO person_attribute_audit (audit_timestamp, audit_type_id, person_id, revision_id, attribute_type_id, attribute_value)
                SELECT CAST(strftime('%s', 'now') AS INTEGER) * 1000, 2, NEW.person_id, revision_id, NEW.attribute_type_id, OLD.attribute_value
                FROM person WHERE id = NEW.person_id;
            END
            """.trimIndent(),
            0,
        )

        execute(
            null,
            """
            CREATE TRIGGER IF NOT EXISTS trigger_person_attribute_delete_audit
            AFTER DELETE ON person_attribute
            FOR EACH ROW
            BEGIN
                UPDATE person
                SET revision_id = revision_id + 1
                WHERE id = OLD.person_id
                  AND NOT EXISTS (SELECT 1 FROM _creation_mode);

                INSERT INTO person_attribute_audit (audit_timestamp, audit_type_id, person_id, revision_id, attribute_type_id, attribute_value)
                SELECT CAST(strftime('%s', 'now') AS INTEGER) * 1000, 3, OLD.person_id, revision_id, OLD.attribute_type_id, OLD.attribute_value
                FROM person WHERE id = OLD.person_id;
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
            sourceTypeQueries.insert(id = 5, name = "API")

            // Seed API session type lookup table
            apiSessionQueries.insertSessionType(id = 1, name = "Monzo")

            // Seed Platform lookup table
            platformQueries.insert(id = 0, name = "SYSTEM")
            platformQueries.insert(id = 1, name = "JVM")
            platformQueries.insert(id = 2, name = "ANDROID")

            // Create system device now (before any seeded entities that need source attribution).
            // Platform 0 = SYSTEM must already exist above.
            deviceQueries.insertSystemDevice(platform_id = 0)
            val systemDeviceId =
                deviceQueries.selectSystemDevice(platform_id = 0).executeAsOneOrNull()
                    ?: deviceQueries.lastInsertRowId().executeAsOne()

            // Create triggers for incremental materialized view refresh
            createIncrementalRefreshTriggers()

            // Create triggers to schedule refresh when the "excluded" attribute is added/removed
            createExcludedAttributeTriggers()

            // Create trigger for category deletion (children inherit grandparent)
            createCategoryDeleteTrigger()

            // Create flag tables and attribute triggers
            createBatchImportTable()
            createCreationModeTable()
            createAttributeTriggers()
            createAccountAttributeTriggers()
            createPersonAttributeTriggers()

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
            entitySourceQueries.insertSource(
                entity_type_id = EntityType.CATEGORY.id,
                entity_id = -1L,
                revision_id = 1,
                source_type_id = SourceType.SYSTEM.id.toLong(),
                device_id = systemDeviceId,
            )

            // Seed well-known attribute types with stable negative IDs so importers can
            // reference them without a DB lookup.
            attributeTypeQueries.insertWithId(id = EXCLUDED_ATTR_TYPE_ID, name = "excluded")
            attributeTypeQueries.insertWithId(id = ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID, name = "account-external-id")
            attributeTypeQueries.insertWithId(id = BUILT_IN_COUNTERPARTY_TYPE_ATTR_TYPE_ID, name = "built-in type")
            attributeTypeQueries.insertWithId(id = ACCOUNT_SORT_CODE_ATTR_TYPE_ID, name = "account-sort-code")
            attributeTypeQueries.insertWithId(id = ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID, name = "account-account-number")

            // Seed the built-in Monzo, Wise and Starling API import strategies (after triggers and
            // system device are created so the INSERT trigger records the initial audit entry/source)
            seedMonzoStrategy(systemDeviceId)
            seedWiseStrategy(systemDeviceId)
            seedStarlingStrategy(systemDeviceId)

            // Seed the built-in CSV import strategies
            seedBuiltInCsvStrategies()

            // Seed currencies with source tracking
            allCurrencies.forEach { currency ->
                val currencyId = currencyRepository.upsertCurrencyByCode(currency.code, currency.displayName)
                entitySourceQueries.insertSource(
                    entity_type_id = EntityType.CURRENCY.id,
                    entity_id = currencyId.id,
                    revision_id = 1,
                    source_type_id = SourceType.SYSTEM.id.toLong(),
                    device_id = systemDeviceId,
                )
            }
        }
    }

    /** Fixed UUID for the built-in Monzo strategy so it can be referenced reliably. */
    private val monzoStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000001")

    /** Seeds the built-in Monzo API import strategy during new-database initialisation. */
    private fun MoneyManagerDatabaseWrapper.seedMonzoStrategy(systemDeviceId: Long) {
        val config =
            ApiStrategyConfigJson(
                baseUrl = "https://api.monzo.com",
                authType = ApiAuthType.BEARER_TOKEN,
                accountsEndpoint =
                    ApiEndpointConfig(
                        path = "/accounts",
                        responseArrayKey = "accounts",
                    ),
                transactionsEndpoint =
                    ApiEndpointConfig(
                        path = "/transactions",
                        responseArrayKey = "transactions",
                        queryParams =
                            listOf(
                                ApiQueryParam(name = "account_id", dynamicSource = "account.id"),
                            ),
                        pagination = ApiPaginationConfig(),
                    ),
                accountMappings =
                    ApiAccountMappings(
                        ownerNameField = "preferred_name",
                    ),
                transactionMappings =
                    ApiTransactionMappings(
                        merchantNameField = "merchant.name",
                        counterpartyNameField = "counterparty.name",
                        counterpartyIdField = "counterparty.id",
                        declineReasonField = "decline_reason",
                        localAmountField = "local_amount",
                        localCurrencyField = "local_currency",
                    ),
                accountNamePrefix = "Monzo: ",
                counterpartyPrefix = "Monzo Counterparty: ",
                builtInCounterpartyRules = monzoAtmRules,
                personExternalIdAttribute = "monzo-external-id",
            )
        val now = Clock.System.now().toEpochMilliseconds()
        apiImportStrategyQueries.insert(
            id = monzoStrategyId.toString(),
            name = "Monzo",
            config_json = ApiStrategyJsonCodec.encode(config),
            created_at = now,
            updated_at = now,
        )
        apiImportStrategyQueries.insertSource(
            strategy_id = monzoStrategyId.toString(),
            revision_id = 1,
            source_type_id = SourceType.SYSTEM.id.toLong(),
            device_id = systemDeviceId,
        )
    }

    /**
     * Monzo ATM-detection expressed declaratively (moved out of the import engine). Each rule routes
     * matching outgoing transactions to a single consolidated "ATM" counterparty account.
     */
    private val monzoAtmRules: List<BuiltInCounterpartyRule> =
        listOf(
            BuiltInCounterpartyRule(
                name = "ATM",
                onlyWhenSign = RuleSign.NEGATIVE,
                predicates = listOf(RulePredicate(path = "atm_fees_detailed", op = PredicateOp.EXISTS)),
            ),
            BuiltInCounterpartyRule(
                name = "ATM",
                onlyWhenSign = RuleSign.NEGATIVE,
                predicates = listOf(RulePredicate(path = "labels", op = PredicateOp.ARRAY_ANY_STARTS_WITH, value = "withdrawal.atm")),
            ),
            BuiltInCounterpartyRule(
                name = "ATM",
                onlyWhenSign = RuleSign.NEGATIVE,
                predicates = listOf(RulePredicate(path = "metadata.mcc", op = PredicateOp.EQUALS, value = "6011")),
            ),
            BuiltInCounterpartyRule(
                name = "ATM",
                onlyWhenSign = RuleSign.NEGATIVE,
                predicates =
                    listOf(
                        RulePredicate(path = "category", op = PredicateOp.EQUALS_IGNORE_CASE, value = "cash"),
                        RulePredicate(path = "merchant", op = PredicateOp.OBJECT_EMPTY),
                        RulePredicate(path = "counterparty", op = PredicateOp.OBJECT_EMPTY),
                    ),
            ),
        )

    /** Fixed UUID for the built-in Wise strategy so it can be referenced reliably. */
    private val wiseStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000002")

    /**
     * Seeds the built-in Wise API import strategy. Wise has a three-level hierarchy
     * (profiles → balances → statements): profiles are fetched as an ancestor endpoint and their id
     * is templated into the balances and statement paths; amounts are decimal with the direction in
     * a separate `type` field; statements are fetched in date windows.
     */
    private fun MoneyManagerDatabaseWrapper.seedWiseStrategy(systemDeviceId: Long) {
        val config =
            ApiStrategyConfigJson(
                baseUrl = "https://api.wise.com",
                authType = ApiAuthType.BEARER_TOKEN,
                ancestorEndpoints =
                    listOf(
                        ApiEndpointConfig(path = "/v1/profiles", responseArrayKey = ""),
                    ),
                accountsEndpoint =
                    ApiEndpointConfig(
                        path = "/v4/profiles/{ancestor[0].id}/balances",
                        responseArrayKey = "",
                        queryParams = listOf(ApiQueryParam(name = "types", value = "STANDARD")),
                    ),
                transactionsEndpoint =
                    ApiEndpointConfig(
                        path = "/v1/profiles/{ancestor[0].id}/balance-statements/{account.id}/statement.json",
                        responseArrayKey = "transactions",
                        queryParams =
                            listOf(
                                ApiQueryParam(name = "currency", dynamicSource = "account.currency"),
                                ApiQueryParam(name = "type", value = "FLAT"),
                            ),
                        // startParam/endParam/windowDays default to Wise's values (intervalStart,
                        // intervalEnd, 469).
                        pagination = ApiPaginationConfig(mode = PaginationMode.DATE_WINDOW),
                    ),
                accountMappings =
                    ApiAccountMappings(
                        // Balances only have a "name" when the user explicitly names them (null by
                        // default), which made account names fall back to the opaque balance id.
                        // Use the currency code instead so accounts are named "Wise: EUR" etc.,
                        // matching the accounts the built-in Wise CSV strategy resolves per-row.
                        descriptionField = "currency",
                        ownersArrayField = null,
                        currencyField = "currency",
                    ),
                transactionMappings =
                    ApiTransactionMappings(
                        amountField = "amount.value",
                        currencyField = "amount.currency",
                        timestampField = "date",
                        descriptionField = "details.description",
                        amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                        signSource = ApiSignSource.FIELD,
                        signField = "type",
                        creditValues = setOf("CREDIT"),
                        idField = "referenceNumber",
                        merchantNameField = "details.merchant.name",
                        counterpartyNameField = "details.senderName",
                    ),
                accountNamePrefix = "Wise: ",
                counterpartyPrefix = "Wise Counterparty: ",
                // Wise balance statements are SCA-protected: a 403 returns an x-2fa-approval one-time
                // token that must be signed with the credential's private key and replayed. Statements
                // are only available via the API for accounts based in these countries.
                signing =
                    ApiSigningConfig(
                        statementCountries = setOf("US", "CA", "AU", "NZ", "SG", "MY"),
                    ),
                // The account holder lives on the profile (the ancestor), not the balance, so people
                // are downloaded/imported separately from the /v1/profiles endpoint.
                peopleDownload =
                    ApiPersonImportConfig(
                        endpoint = ApiEndpointConfig(path = "/v1/profiles", responseArrayKey = ""),
                        firstNameField = "details.firstName",
                        lastNameField = "details.lastName",
                        preferredNameField = "details.preferredName",
                        fallbackNameField = "details.name",
                        accountOwnerAncestorExpr = "ancestor[0].id",
                    ),
                personExternalIdAttribute = "wise-external-id",
            )
        val now = Clock.System.now().toEpochMilliseconds()
        apiImportStrategyQueries.insert(
            id = wiseStrategyId.toString(),
            name = "Wise",
            config_json = ApiStrategyJsonCodec.encode(config),
            created_at = now,
            updated_at = now,
        )
        apiImportStrategyQueries.insertSource(
            strategy_id = wiseStrategyId.toString(),
            revision_id = 1,
            source_type_id = SourceType.SYSTEM.id.toLong(),
            device_id = systemDeviceId,
        )
    }

    /** Fixed UUID for the built-in Starling strategy so it can be referenced reliably. */
    private val starlingStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000005")

    /**
     * Seeds the built-in Starling Bank API import strategy. Starling is a flat two-level API like
     * Monzo: a single Bearer token lists accounts, and each account's transaction feed is fetched
     * from a path templated with the account id and its `defaultCategory`. Amounts are integer minor
     * units and the direction lives in a separate `direction` field (IN/OUT). The whole feed is
     * returned in one response, so no pagination is configured. The account holder is a single global
     * object (`/api/v2/account-holder/individual`) linked to every imported account.
     */
    private fun MoneyManagerDatabaseWrapper.seedStarlingStrategy(systemDeviceId: Long) {
        val config =
            ApiStrategyConfigJson(
                baseUrl = "https://api.starlingbank.com",
                authType = ApiAuthType.BEARER_TOKEN,
                accountsEndpoint =
                    ApiEndpointConfig(
                        path = "/api/v2/accounts",
                        responseArrayKey = "accounts",
                    ),
                transactionsEndpoint =
                    ApiEndpointConfig(
                        // {account.defaultCategory} resolves the account's defaultCategory from its raw
                        // JSON; the feed endpoint returns the full history in a single response.
                        path = "/api/v2/feed/account/{account.id}/category/{account.defaultCategory}",
                        responseArrayKey = "feedItems",
                    ),
                accountMappings =
                    ApiAccountMappings(
                        idField = "accountUid",
                        descriptionField = "name",
                        currencyField = "currency",
                        ownersArrayField = null,
                    ),
                transactionMappings =
                    ApiTransactionMappings(
                        amountField = "amount.minorUnits",
                        currencyField = "amount.currency",
                        timestampField = "transactionTime",
                        descriptionField = "reference",
                        amountFormat = ApiAmountFormat.MINOR_UNITS_INTEGER,
                        signSource = ApiSignSource.FIELD,
                        signField = "direction",
                        creditValues = setOf("IN"),
                        idField = "feedItemUid",
                        counterpartyNameField = "counterPartyName",
                    ),
                accountNamePrefix = "Starling: ",
                counterpartyPrefix = "Starling Counterparty: ",
                // The account holder is global (one per connection) and returned as a single object,
                // so it is linked to every account imported in the session.
                peopleDownload =
                    ApiPersonImportConfig(
                        endpoint = ApiEndpointConfig(path = "/api/v2/account-holder/individual", responseArrayKey = ""),
                        firstNameField = "firstName",
                        lastNameField = "lastName",
                        ownsAllAccounts = true,
                    ),
                personExternalIdAttribute = "starling-external-id",
            )
        val now = Clock.System.now().toEpochMilliseconds()
        apiImportStrategyQueries.insert(
            id = starlingStrategyId.toString(),
            name = "Starling",
            config_json = ApiStrategyJsonCodec.encode(config),
            created_at = now,
            updated_at = now,
        )
        apiImportStrategyQueries.insertSource(
            strategy_id = starlingStrategyId.toString(),
            revision_id = 1,
            source_type_id = SourceType.SYSTEM.id.toLong(),
            device_id = systemDeviceId,
        )
    }

    /** Fixed UUID for the built-in Wise CSV strategy so it can be referenced reliably. */
    val wiseCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000003")

    /** Fixed UUID for the built-in Monzo CSV strategy so it can be referenced reliably. */
    val monzoCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000004")

    /** Account name prefix shared with the Wise API import strategy ("Wise: " + currency code). */
    private const val WISE_ACCOUNT_PREFIX = "Wise: "

    /**
     * Deterministic FieldMappingId for built-in CSV strategies.
     * Each strategy uses its own [strategyGroup] so ids never collide across strategies.
     */
    private fun builtInCsvMappingId(
        strategyGroup: Int,
        index: Int,
    ): FieldMappingId =
        FieldMappingId(
            Uuid.parse(
                "00000000-0000-0000-${strategyGroup.toString().padStart(4, '0')}-${index.toString().padStart(12, '0')}",
            ),
        )

    private fun wiseCsvMappingId(index: Int): FieldMappingId = builtInCsvMappingId(strategyGroup = 1, index = index)

    private fun monzoCsvMappingId(index: Int): FieldMappingId = builtInCsvMappingId(strategyGroup = 2, index = index)

    /** All built-in CSV import strategies seeded into a fresh database. */
    fun builtInCsvStrategies(now: Instant): List<CsvImportStrategy> =
        listOf(
            buildWiseCsvStrategy(now),
            buildMonzoCsvStrategy(now),
        )

    /**
     * Builds the built-in Wise CSV import strategy for Wise's transaction-history.csv export.
     *
     * Each row carries both sides of the transaction (Source and Target columns) and a Direction
     * column saying which side is the user's Wise balance. A row preprocessing rule swaps the
     * Source/Target values for IN rows so all field mappings can read the Source-side columns,
     * then flips the resolved accounts. The per-currency Wise balance is resolved by templating
     * the currency code into the account name used by the Wise API import ("Wise: EUR").
     * Balance-to-balance conversions (same name on both sides, different currencies) route the
     * target to the other Wise balance instead of a counterparty account.
     *
     * Wise's export omits interest-earned rows, but each monthly "Assets fee" (wise-id like
     * `ACCRUAL_CHARGE-…`) implies one, so a companion transaction rule flags those transfers
     * for manual entry of the mirroring interest transfer.
     */
    fun buildWiseCsvStrategy(now: Instant): CsvImportStrategy {
        val sourceAccount =
            TemplateAccountMapping(
                id = wiseCsvMappingId(1),
                fieldType = TransferField.SOURCE_ACCOUNT,
                columnName = "Source currency",
                prefix = WISE_ACCOUNT_PREFIX,
            )
        val targetAccount =
            ConditionalAccountMapping(
                id = wiseCsvMappingId(2),
                fieldType = TransferField.TARGET_ACCOUNT,
                conditions =
                    listOf(
                        RowCondition("Source name", RowConditionOperator.EQUALS_COLUMN, otherColumnName = "Target name"),
                        RowCondition("Source name", RowConditionOperator.IS_NOT_BLANK),
                        RowCondition("Source currency", RowConditionOperator.NOT_EQUALS_COLUMN, otherColumnName = "Target currency"),
                    ),
                whenTrue =
                    TemplateAccountMapping(
                        id = wiseCsvMappingId(3),
                        fieldType = TransferField.TARGET_ACCOUNT,
                        columnName = "Target currency",
                        prefix = WISE_ACCOUNT_PREFIX,
                    ),
                whenFalse =
                    AccountLookupMapping(
                        id = wiseCsvMappingId(4),
                        fieldType = TransferField.TARGET_ACCOUNT,
                        columnName = "Target name",
                        fallbackColumns = listOf("Source name"),
                    ),
            )
        val fieldMappings =
            mapOf(
                TransferField.SOURCE_ACCOUNT to sourceAccount,
                TransferField.TARGET_ACCOUNT to targetAccount,
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        id = wiseCsvMappingId(5),
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = "Created on",
                        dateFormat = "yyyy-MM-dd",
                        dateTimeFormat = "yyyy-MM-dd HH:mm:ss",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(
                        id = wiseCsvMappingId(6),
                        fieldType = TransferField.DESCRIPTION,
                        columnName = "Reference",
                        fallbackColumns = listOf("Note", "Category", "Target name"),
                    ),
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        id = wiseCsvMappingId(7),
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "Source amount (after fees)",
                        // The amount column is net of fees, but on OUT rows the fee also left the
                        // balance (e.g. ATM: 200.00 withdrawn + 7.29 fee = 207.29 debited). On IN
                        // rows the after-fees amount is already exactly what arrived.
                        feeColumnName = "Source fee amount",
                        feeConditions = listOf(RowCondition("Direction", RowConditionOperator.EQUALS_VALUE, value = "OUT")),
                    ),
                TransferField.CURRENCY to
                    CurrencyLookupMapping(
                        id = wiseCsvMappingId(8),
                        fieldType = TransferField.CURRENCY,
                        columnName = "Source currency",
                    ),
                TransferField.TIMEZONE to
                    HardCodedTimezoneMapping(
                        id = wiseCsvMappingId(9),
                        fieldType = TransferField.TIMEZONE,
                        timezoneId = "UTC",
                    ),
            )
        val rowRules =
            listOf(
                RowPreprocessingRule(
                    conditions = listOf(RowCondition("Direction", RowConditionOperator.EQUALS_VALUE, value = "IN")),
                    columnSwaps =
                        listOf(
                            ColumnPairSwap("Source name", "Target name"),
                            ColumnPairSwap("Source amount (after fees)", "Target amount (after fees)"),
                            ColumnPairSwap("Source currency", "Target currency"),
                        ),
                    flipSourceAndTarget = true,
                ),
            )
        val attributeMappings =
            listOf(
                AttributeColumnMapping("ID", "wise-id", isUniqueIdentifier = true),
                AttributeColumnMapping("Status", "wise-status"),
                AttributeColumnMapping("Direction", "wise-direction"),
                AttributeColumnMapping("Exchange rate", "wise-exchange-rate"),
                AttributeColumnMapping("Category", "wise-category"),
                AttributeColumnMapping("Note", "note"),
                AttributeColumnMapping("Created by", "wise-created-by"),
            )
        val companionRules =
            listOf(
                CompanionTransactionRule(
                    name = "Interest earned",
                    matchAttributeName = "wise-id",
                    matchValuePattern = "ACCRUAL_CHARGE-%",
                    linkAttributeName = "wise-interest-for",
                    companionDescription = "Interest earned",
                ),
            )
        val identificationColumns =
            setOf(
                "ID",
                "Status",
                "Direction",
                "Created on",
                "Finished on",
                "Source fee amount",
                "Source fee currency",
                "Target fee amount",
                "Target fee currency",
                "Source name",
                "Source amount (after fees)",
                "Source currency",
                "Target name",
                "Target amount (after fees)",
                "Target currency",
                "Exchange rate",
                "Reference",
                "Batch",
                "Created by",
                "Category",
                "Note",
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(wiseCsvStrategyId),
            name = "Wise CSV",
            identificationColumns = identificationColumns,
            fieldMappings = fieldMappings,
            attributeMappings = attributeMappings,
            rowPreprocessingRules = rowRules,
            companionTransactionRules = companionRules,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Builds the built-in Monzo CSV import strategy for Monzo's transaction export.
     *
     * No SOURCE_ACCOUNT mapping is seeded (account ids are database-specific); the user picks
     * the Monzo account when applying the strategy. Positive amounts flow INTO the account, so
     * flipAccountsOnPositive swaps source/target for credits. Transactions are deduplicated by
     * the Transaction ID column on re-import.
     */
    fun buildMonzoCsvStrategy(now: Instant): CsvImportStrategy {
        val fieldMappings =
            mapOf(
                TransferField.TARGET_ACCOUNT to
                    AccountLookupMapping(
                        id = monzoCsvMappingId(1),
                        fieldType = TransferField.TARGET_ACCOUNT,
                        columnName = "Name",
                        fallbackColumns = listOf("Type"),
                    ),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        id = monzoCsvMappingId(2),
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = "Date",
                        dateFormat = "dd/MM/yyyy",
                        timeColumnName = "Time",
                        timeFormat = "HH:mm:ss",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(
                        id = monzoCsvMappingId(3),
                        fieldType = TransferField.DESCRIPTION,
                        columnName = "Description",
                        fallbackColumns = listOf("Type"),
                    ),
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        id = monzoCsvMappingId(4),
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "Amount",
                        flipAccountsOnPositive = true,
                    ),
                TransferField.CURRENCY to
                    CurrencyLookupMapping(
                        id = monzoCsvMappingId(5),
                        fieldType = TransferField.CURRENCY,
                        columnName = "Currency",
                    ),
                TransferField.TIMEZONE to
                    HardCodedTimezoneMapping(
                        id = monzoCsvMappingId(6),
                        fieldType = TransferField.TIMEZONE,
                        timezoneId = "Europe/London",
                    ),
            )
        val attributeMappings =
            listOf(
                AttributeColumnMapping("Transaction ID", "Monzo Transaction ID", isUniqueIdentifier = true),
                AttributeColumnMapping("Emoji", "Emoji"),
                AttributeColumnMapping("Category", "Monzo Category"),
                AttributeColumnMapping("Notes and #tags", "Notes and #tags"),
                AttributeColumnMapping("Address", "Address"),
                AttributeColumnMapping("Receipt", "Receipt"),
                AttributeColumnMapping("Type", "Type"),
            )
        val identificationColumns =
            setOf(
                "Transaction ID",
                "Date",
                "Time",
                "Type",
                "Name",
                "Emoji",
                "Category",
                "Amount",
                "Currency",
                "Local amount",
                "Local currency",
                "Notes and #tags",
                "Address",
                "Receipt",
                "Description",
                "Category split",
                "Money Out",
                "Money In",
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(monzoCsvStrategyId),
            name = "Monzo CSV",
            identificationColumns = identificationColumns,
            fieldMappings = fieldMappings,
            attributeMappings = attributeMappings,
            createdAt = now,
            updatedAt = now,
        )
    }

    /** Seeds the built-in CSV import strategies during new-database initialisation. */
    private fun MoneyManagerDatabaseWrapper.seedBuiltInCsvStrategies() {
        val now = Clock.System.now()
        for (strategy in builtInCsvStrategies(now)) {
            csvImportStrategyQueries.insert(
                id = strategy.id.id.toString(),
                name = strategy.name,
                identification_columns_json = FieldMappingJsonCodec.encodeColumns(strategy.identificationColumns),
                field_mappings_json = FieldMappingJsonCodec.encode(strategy.fieldMappings),
                attribute_mappings_json = FieldMappingJsonCodec.encodeAttributeMappings(strategy.attributeMappings),
                row_rules_json = FieldMappingJsonCodec.encodeRowRules(strategy.rowPreprocessingRules),
                companion_rules_json = FieldMappingJsonCodec.encodeCompanionRules(strategy.companionTransactionRules),
                created_at = now.toEpochMilliseconds(),
                updated_at = now.toEpochMilliseconds(),
            )
        }
    }
}
