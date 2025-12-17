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
     * Tables to exclude from audit trail.
     * Includes audit tables themselves, materialized views, system tables, and CSV import tables.
     */
    @Suppress("PrivatePropertyName", "ktlint:standard:property-naming")
    private val EXCLUDED_FROM_AUDIT =
        setOf(
            "AuditType",
            "Account_Audit",
            "Currency_Audit",
            "Category_Audit",
            "Transfer_Audit",
            "AccountBalanceMaterializedView",
            "RunningBalanceMaterializedView",
            "PendingMaterializedViewChanges",
            "sqlite_sequence",
            "CsvImportMetadata",
            "CsvColumnMetadata",
            "CsvImportStrategy",
        )

    /**
     * Prefix for dynamically created CSV import tables.
     */
    private val csvTablePrefix = "csv_import_"

    /**
     * Checks if a table should be excluded from audit.
     */
    fun isExcludedFromAudit(tableName: String): Boolean = tableName in EXCLUDED_FROM_AUDIT || tableName.startsWith(csvTablePrefix)

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
