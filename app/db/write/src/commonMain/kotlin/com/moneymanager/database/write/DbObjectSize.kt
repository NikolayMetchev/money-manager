package com.moneymanager.database.write

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.moneymanager.database.rawsql.RawSql

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
 * Returns the SQLite object-level size breakdown using `dbstat`, sorted by descending on-disk size.
 * If `dbstat` is unavailable in the current SQLite build, returns an empty list.
 *
 * Hand-written SQL (see [RawSql]): `dbstat` and `pragma_table_info` are virtual tables SQLDelight can't
 * type, and counting every table's rows needs the table name as a parameter.
 */
suspend fun MoneyManagerDatabaseWrapper.getDbSizeBreakdown(): List<DbObjectSize> {
    val raw = RawSql(driver)
    return runCatching {
        val result =
            raw
                .query(
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
                ) { cursor ->
                    val objectName = cursor.getString(0)
                    val pageCount = cursor.getLong(1)
                    val totalBytes = cursor.getLong(2)
                    if (objectName != null && pageCount != null && totalBytes != null) {
                        listOf(DbObjectSize(objectName = objectName, pageCount = pageCount, totalBytes = totalBytes))
                    } else {
                        emptyList()
                    }
                }.awaitAsList()
                .flatten()
        // Enrich with object type, plus row/column counts for tables (non-tables stay null).
        val types = raw.sqliteMasterTypes()
        val columnCounts = raw.tableColumnCounts()
        result.map { row ->
            val columnCount = columnCounts[row.objectName]
            row.copy(
                objectType = types[row.objectName] ?: "internal",
                rowCount = if (columnCount != null) raw.tableRowCount(row.objectName) else null,
                columnCount = columnCount,
            )
        }
    }.getOrDefault(emptyList())
}

/** Returns the object type (table/index/trigger/view) keyed by name, from `sqlite_master`. */
private suspend fun RawSql.sqliteMasterTypes(): Map<String, String> =
    query("SELECT name, type FROM sqlite_master WHERE name IS NOT NULL AND type IS NOT NULL") { cursor ->
        cursor.getString(0)!! to cursor.getString(1)!!
    }.awaitAsList().toMap()

/** Returns column counts keyed by table name (tables only, from `sqlite_master`). */
private suspend fun RawSql.tableColumnCounts(): Map<String, Long> =
    query(
        """
        SELECT m.name, COUNT(ti.cid)
        FROM sqlite_master m
        JOIN pragma_table_info(m.name) ti
        WHERE m.type = 'table'
        GROUP BY m.name
        """.trimIndent(),
    ) { cursor -> cursor.getString(0)!! to cursor.getLong(1)!! }.awaitAsList().toMap()

/** Returns `COUNT(*)` for a table, or null if it can't be queried. */
private suspend fun RawSql.tableRowCount(tableName: String): Long? =
    runCatching {
        // tableName comes from sqlite_master (trusted schema); still quote-escape defensively.
        val quoted = "\"" + tableName.replace("\"", "\"\"") + "\""
        query("SELECT COUNT(*) FROM $quoted") { it.getLong(0)!! }.awaitAsOneOrNull()
    }.getOrNull()
