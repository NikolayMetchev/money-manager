package com.moneymanager.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.moneymanager.database.sql.MoneyManagerDatabase

class MoneyManagerDatabaseWrapper(private val driver: SqlDriver) : MoneyManagerDatabase by MoneyManagerDatabase(driver) {
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
            "category_audit",
            "transfer_audit",
            "person_audit",
            "person_account_ownership_audit",
            "transfer_attribute_audit",
            "account_balance_materialized_view",
            "running_balance_materialized_view",
            "pending_materialized_view_changes",
            "sqlite_sequence",
            "csv_account_mapping",
            "csv_column_metadata",
            "csv_import_error",
            "csv_import_metadata",
            "csv_import_strategy",
            "transaction_id",
            "source_type",
            "transfer_source",
            "csv_transfer_source",
            "platform",
            "os",
            "machine",
            "device_make",
            "device_model",
            "device",
            "attribute_type",
            "transfer_attribute",
            "_import_batch",
            "_creation_mode",
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
            tableName.startsWith(csvTablePrefix) ||
            tableName.endsWith("_audit")

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
}
