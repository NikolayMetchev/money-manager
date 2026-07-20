package com.moneymanager.database.write

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.database.sql.write.MoneyManagerDatabase as WriteDatabase

/**
 * Composes the read and write SQLDelight databases over a single [SqlDriver].
 *
 * The schema and every `*Select.sq` live in :app:db:read (generating the read [MoneyManagerDatabase]
 * delegated below — this exposes the `*SelectQueries`). Every `*Write.sq` lives in :app:db:write
 * (generating a separate write database in com.moneymanager.database.sql.write). Both wrap the SAME
 * driver, so writes through [writeDb] are seen by read-side query listeners. The `*WriteQueries` are
 * re-exposed under their original names so existing repository/seed code (`database.accountWriteQueries`,
 * `database.accountSelectQueries`, …) compiles unchanged.
 */
class MoneyManagerDatabaseWrapper(
    private val driver: SqlDriver,
) : MoneyManagerDatabase by MoneyManagerDatabase(driver) {
    private val writeDb = WriteDatabase(driver)

    val accountAttributeWriteQueries get() = writeDb.accountAttributeWriteQueries
    val accountMappingWriteQueries get() = writeDb.accountMappingWriteQueries
    val accountMergeWriteQueries get() = writeDb.accountMergeWriteQueries
    val accountWriteQueries get() = writeDb.accountWriteQueries
    val assetWriteQueries get() = writeDb.assetWriteQueries
    val cryptoWriteQueries get() = writeDb.cryptoWriteQueries
    val apiImportStrategyWriteQueries get() = writeDb.apiImportStrategyWriteQueries
    val apiSessionWriteQueries get() = writeDb.apiSessionWriteQueries
    val attributeTypeWriteQueries get() = writeDb.attributeTypeWriteQueries
    val categoryWriteQueries get() = writeDb.categoryWriteQueries
    val csvImportStrategyWriteQueries get() = writeDb.csvImportStrategyWriteQueries
    val csvImportWriteQueries get() = writeDb.csvImportWriteQueries
    val currencyWriteQueries get() = writeDb.currencyWriteQueries
    val deviceWriteQueries get() = writeDb.deviceWriteQueries
    val entitySourceWriteQueries get() = writeDb.entitySourceWriteQueries
    val exchangeOrderWriteQueries get() = writeDb.exchangeOrderWriteQueries
    val importDirectoryWriteQueries get() = writeDb.importDirectoryWriteQueries
    val maintenanceWriteQueries get() = writeDb.maintenanceWriteQueries
    val personAttributeWriteQueries get() = writeDb.personAttributeWriteQueries
    val personWriteQueries get() = writeDb.personWriteQueries
    val passThroughAccountWriteQueries get() = writeDb.passThroughAccountWriteQueries
    val qifImportWriteQueries get() = writeDb.qifImportWriteQueries
    val relationshipTypeWriteQueries get() = writeDb.relationshipTypeWriteQueries
    val settingsWriteQueries get() = writeDb.settingsWriteQueries
    val tradeWriteQueries get() = writeDb.tradeWriteQueries
    val transactionIdWriteQueries get() = writeDb.transactionIdWriteQueries
    val transferAttributeWriteQueries get() = writeDb.transferAttributeWriteQueries
    val transferRelationshipWriteQueries get() = writeDb.transferRelationshipWriteQueries
    val transferWriteQueries get() = writeDb.transferWriteQueries

    data class DbObjectSize(
        val objectName: String,
        val pageCount: Long,
        val totalBytes: Long,
        /** `sqlite_master` object type (table/index/trigger/view), or "internal" for SQLite-internal objects. */
        val objectType: String = "internal",
        /** Number of rows; null for objects that aren't tables (e.g. indexes, internal objects). */
        val rowCount: Long? = null,
        /** Number of columns; null for objects that aren't tables. */
        val columnCount: Long? = null,
    )

    /**
     * Closes the underlying driver/connection. Used for short-lived databases opened off the main
     * session (e.g. rehydrating a remote snapshot) so the file can be safely reopened afterwards.
     */
    fun close() = driver.close()

    /**
     * A token reflecting how much logical, syncable state has changed since it was last captured. It
     * folds together three things:
     *  - the total number of rows across the append-only `*_audit` tables (every entity
     *    create/update/delete appends one),
     *  - the values of the singleton `settings` row (default currency, last QIF account), which is
     *    excluded from the audit trail but is still part of the synced database and must register as a
     *    change — otherwise e.g. setting the default currency would never be detected as unsynced, and
     *  - a rolling hash of the `account_mapping` table, which is user-managed first-class data but is
     *    not audited (see EXCLUDED_FROM_AUDIT), so its edits must still register as a change.
     *
     * Derived materialized-view rebuilds append no audit rows and don't touch settings, so this stays a
     * stable "has the data changed since last sync?" signal that ignores our own view maintenance. Only
     * equality of two tokens is meaningful (it is a hash, not a counter).
     */
    fun dataChangeToken(): Long {
        val auditTables = mutableListOf<String>()
        executeQuery(
            null,
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name GLOB '*_audit'",
            { cursor ->
                while (cursor.next().value) {
                    cursor.getString(0)?.let(auditTables::add)
                }
                QueryResult.Unit
            },
            0,
        )
        val auditRowCount =
            auditTables.sumOf { table ->
                executeQuery(
                    null,
                    "SELECT COUNT(*) FROM $table",
                    { cursor ->
                        cursor.next()
                        QueryResult.Value(cursor.getLong(0)!!)
                    },
                    0,
                ).value
            }
        return foldAccountMappingsInto(foldSettingsInto(auditRowCount))
    }

    /**
     * Mixes the non-audited `account_mapping` table into [base] with a rolling hash. Row count catches
     * inserts/deletes; summing ids catches which rows exist; summing updated_at catches in-place edits.
     */
    private fun foldAccountMappingsInto(base: Long): Long {
        var token = base
        executeQuery(
            null,
            "SELECT COUNT(*), COALESCE(SUM(id), 0), COALESCE(SUM(updated_at), 0) FROM account_mapping",
            { cursor ->
                if (cursor.next().value) {
                    token = token * SETTINGS_HASH_PRIME + cursor.getLong(0)!!
                    token = token * SETTINGS_HASH_PRIME + cursor.getLong(1)!!
                    token = token * SETTINGS_HASH_PRIME + cursor.getLong(2)!!
                }
                QueryResult.Unit
            },
            0,
        )
        return token
    }

    /**
     * Mixes the non-audited `settings` row (id = 1) into [base] with a rolling hash, so a change to any
     * tracked setting (default currency, last QIF account) yields a different token even though the
     * settings table appends no audit rows. A missing row leaves [base] unchanged.
     */
    private fun foldSettingsInto(base: Long): Long {
        var token = base
        executeQuery(
            null,
            "SELECT COALESCE(default_currency_id, 0), COALESCE(last_qif_account_id, 0) FROM settings WHERE id = 1",
            { cursor ->
                if (cursor.next().value) {
                    token = token * SETTINGS_HASH_PRIME + cursor.getLong(0)!!
                    token = token * SETTINGS_HASH_PRIME + cursor.getLong(1)!!
                }
                QueryResult.Unit
            },
            0,
        )
        return token
    }

    /**
     * Returns the SQLite object-level size breakdown using `dbstat`.
     *
     * The list is sorted by descending on-disk size.
     * If `dbstat` is unavailable in the current SQLite build, returns an empty list.
     */
    fun getDbSizeBreakdown(): List<DbObjectSize> =
        runCatching {
            val result = mutableListOf<DbObjectSize>()
            executeQuery(
                null,
                """
                SELECT
                    name,
                    COUNT(*) AS page_count,
                    SUM(pgsize) AS total_bytes
                FROM dbstat
                WHERE name IS NOT NULL AND name != ''
                GROUP BY name
                ORDER BY total_bytes DESC, name ASC
                """.trimIndent(),
                { cursor ->
                    while (cursor.next().value) {
                        val objectName = cursor.getString(0)
                        val pageCount = cursor.getLong(1)
                        val totalBytes = cursor.getLong(2)
                        if (objectName != null && pageCount != null && totalBytes != null) {
                            result += DbObjectSize(objectName = objectName, pageCount = pageCount, totalBytes = totalBytes)
                        }
                    }
                    QueryResult.Unit
                },
                0,
            )
            // Enrich with object type, plus row/column counts for tables (non-tables stay null).
            val types = sqliteMasterTypes()
            val columnCounts = tableColumnCounts()
            result.map { row ->
                val columnCount = columnCounts[row.objectName]
                row.copy(
                    objectType = types[row.objectName] ?: "internal",
                    rowCount = if (columnCount != null) tableRowCount(row.objectName) else null,
                    columnCount = columnCount,
                )
            }
        }.getOrDefault(emptyList())

    /** Returns the object type (table/index/trigger/view) keyed by name, from `sqlite_master`. */
    private fun sqliteMasterTypes(): Map<String, String> {
        val types = mutableMapOf<String, String>()
        executeQuery(
            null,
            "SELECT name, type FROM sqlite_master WHERE name IS NOT NULL AND type IS NOT NULL",
            { cursor ->
                while (cursor.next().value) {
                    val name = cursor.getString(0)
                    val type = cursor.getString(1)
                    if (name != null && type != null) {
                        types[name] = type
                    }
                }
                QueryResult.Unit
            },
            0,
        )
        return types
    }

    /** Returns column counts keyed by table name (tables only, from `sqlite_master`). */
    private fun tableColumnCounts(): Map<String, Long> {
        val counts = mutableMapOf<String, Long>()
        executeQuery(
            null,
            """
            SELECT m.name, COUNT(ti.cid)
            FROM sqlite_master m
            JOIN pragma_table_info(m.name) ti
            WHERE m.type = 'table'
            GROUP BY m.name
            """.trimIndent(),
            { cursor ->
                while (cursor.next().value) {
                    val name = cursor.getString(0)
                    val count = cursor.getLong(1)
                    if (name != null && count != null) {
                        counts[name] = count
                    }
                }
                QueryResult.Unit
            },
            0,
        )
        return counts
    }

    /** Returns `COUNT(*)` for a table, or null if it can't be queried. */
    private fun tableRowCount(tableName: String): Long? =
        runCatching {
            // tableName comes from sqlite_master (trusted schema); still quote-escape defensively.
            val quoted = "\"" + tableName.replace("\"", "\"\"") + "\""
            var count: Long? = null
            executeQuery(
                null,
                "SELECT COUNT(*) FROM $quoted",
                { cursor ->
                    if (cursor.next().value) {
                        count = cursor.getLong(0)
                    }
                    QueryResult.Unit
                },
                0,
            )
            count
        }.getOrNull()

    /**
     * Execute a SQL statement on the database.
     * Delegates to the underlying SqlDriver.
     */
    fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
    ) = driver.execute(identifier, sql, parameters)

    /**
     * Execute a SQL query on the database.
     * Delegates to the underlying SqlDriver.
     */
    fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
    ): QueryResult<R> = driver.executeQuery(identifier, sql, mapper, parameters)

    /**
     * Execute a SQL statement with string parameters.
     * Used for dynamic SQL where parameters need to be bound safely.
     */
    fun executeWithParams(
        sql: String,
        parameterCount: Int,
        parameters: List<String>,
    ) {
        driver.execute(null, sql, parameterCount) {
            parameters.forEachIndexed { index, value ->
                bindString(index, value)
            }
        }
    }

    /**
     * Enables creation mode by inserting a row into _creation_mode.
     * When creation mode is active, attribute triggers record audit but don't bump revision.
     * Use this when creating a transfer with initial attributes.
     * Must be paired with [endCreationMode] to restore normal trigger behavior.
     */
    fun beginCreationMode() {
        execute(null, "INSERT INTO _creation_mode (active) VALUES (1)", 0)
    }

    /**
     * Disables creation mode by clearing _creation_mode table.
     * Restores normal trigger behavior (bump revision on attribute changes).
     */
    fun endCreationMode() {
        execute(null, "DELETE FROM _creation_mode", 0)
    }

    /**
     * Tables to exclude from audit trail.
     * Includes audit tables themselves, materialized views, system tables, and CSV import tables.
     */
    @Suppress("PrivatePropertyName", "ktlint:standard:property-naming")
    private val EXCLUDED_FROM_AUDIT =
        setOf(
            "audit_type",
            "account_audit",
            "currency_audit",
            "crypto_audit",
            "trade_audit",
            "exchange_order_audit",
            "exchange_order_trade",
            "asset",
            "category_audit",
            "transfer_audit",
            "person_audit",
            "person_attribute_audit",
            "person_account_ownership_audit",
            "transfer_attribute_audit",
            "account_attribute_audit",
            "api_import_strategy_audit",
            "api_import_strategy_source",
            "csv_import_strategy_audit",
            "csv_import_strategy_source",
            "import_directory_provider",
            "import_directory",
            "import_directory_audit",
            "import_directory_source",
            "import_directory_file",
            "import_directory_file_csv_import",
            "import_directory_file_qif_import",
            "android_metadata",
            "account_balance_materialized_view",
            "running_balance_materialized_view",
            "pending_materialized_view_changes",
            "sqlite_sequence",
            "account_mapping",
            "csv_column_metadata",
            "csv_import_error",
            "csv_import_metadata",
            "csv_import_strategy",
            "xlsx_import_blob",
            "xlsx_import_strategy",
            "transaction_id",
            "source_type",
            "entity_source",
            "csv_entity_source",
            "qif_entity_source",
            "entity_type",
            "qif_import_metadata",
            "qif_record",
            "qif_import_application",
            "qif_import_error",
            "api_entity_source",
            "platform",
            "os",
            "machine",
            "device_make",
            "device_model",
            "device",
            "attribute_type",
            "transfer_attribute",
            "relationship_type",
            "pass_through_account",
            "pass_through_account_audit",
            "transfer_relationship",
            "account_attribute",
            "person_attribute",
            "_import_batch",
            "_creation_mode",
            // Account-merge reversal records; they are the audit/undo data, not audited entities.
            "account_merge",
            "account_merge_transfer",
            "settings",
            "api_credential",
            "api_session",
            "api_import",
            "api_request",
            "api_request_header",
            "api_response",
            "api_response_transaction",
        )

    /**
     * Prefix for dynamically created CSV import tables.
     */
    private val csvTablePrefix = "csv_import_"

    /**
     * Checks if a table should be excluded from audit.
     */
    fun isExcludedFromAudit(tableName: String): Boolean =
        tableName in EXCLUDED_FROM_AUDIT ||
            tableName.startsWith(csvTablePrefix)

    /**
     * Gets all auditable tables from the database.
     * Queries sqlite_master and excludes tables in EXCLUDED_FROM_AUDIT set.
     *
     * @return List of auditable table names, sorted alphabetically
     */
    fun getAuditableTables(): List<String> {
        val tables = mutableListOf<String>()
        executeQuery(
            null,
            """
            SELECT name FROM sqlite_master
            WHERE type = 'table'
            AND name NOT LIKE 'sqlite_%'
            ORDER BY name
            """.trimIndent(),
            { cursor ->
                while (cursor.next().value) {
                    val tableName = cursor.getString(0) ?: continue
                    if (!isExcludedFromAudit(tableName)) {
                        tables.add(tableName)
                    }
                }
                QueryResult.Unit
            },
            0,
        )
        return tables
    }

    /**
     * Gets all column names for a specific table.
     * Uses PRAGMA table_info() to query column metadata.
     *
     * @param tableName The name of the table to query
     * @return List of column names in the order they appear in the table
     */
    fun getTableColumns(tableName: String): List<String> {
        val columns = mutableListOf<String>()
        executeQuery(
            null,
            "PRAGMA table_info($tableName)",
            { cursor ->
                while (cursor.next().value) {
                    val columnName = cursor.getString(1) ?: continue
                    columns.add(columnName)
                }
                QueryResult.Unit
            },
            0,
        )
        return columns
    }

    private companion object {
        /** Odd prime used to mix settings values into the data-change token (collision-resistant fold). */
        const val SETTINGS_HASH_PRIME = 1_000_003L
    }
}
