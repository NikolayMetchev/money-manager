package com.moneymanager.csvimporter

import com.moneymanager.coroutines.ioDispatcher
import com.moneymanager.csv.CsvParseResult
import com.moneymanager.domain.model.SqliteRowRef
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import kotlinx.coroutines.withContext

/** An unreadable SQLite snapshot or a query that SQLite could not run. */
class SqliteReadException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

internal data class SqliteProjection(
    val strategy: CsvImportStrategy?,
    val data: CsvParseResult,
)

/** A database without an installed projection still has a browsable original file. */
internal suspend fun readSqliteImport(
    fileName: String,
    bytes: ByteArray,
    strategies: List<CsvImportStrategy>,
    headersOnly: Boolean = false,
): SqliteProjection {
    val matching = strategies.filter { it.config.sqliteQuery != null && it.supportsFile(fileName) }
    val queries =
        matching.associate {
            val query = requireNotNull(it.config.sqliteQuery).trim().removeSuffix(";")
            it.name to if (headersOnly) "SELECT * FROM (\n$query\n) LIMIT 0" else query
        }
    val projection = withContext(ioDispatcher) { querySqlite(bytes, queries) }
    if (projection == null) return SqliteProjection(null, CsvParseResult(emptyList(), emptyList()))
    val (name, result) = projection
    val strategy = matching.single { it.name == name }
    require(strategy.matchesColumns(result.headers.toSet()) && SqliteRowRef.COLUMN in result.headers) {
        "SQLite strategy $name must project its identification columns and ${SqliteRowRef.COLUMN}"
    }
    return SqliteProjection(strategy, result)
}

internal typealias SqliteReader = (bytes: ByteArray, queries: Map<String, String>) -> Pair<String, CsvParseResult>?

internal expect fun sqliteReader(): SqliteReader?

private fun querySqlite(
    bytes: ByteArray,
    queries: Map<String, String>,
): Pair<String, CsvParseResult>? {
    val reader = sqliteReader() ?: throw SqliteReadException("SQLite file imports are available on desktop and Android")
    return reader(bytes, queries)
}

/** Finds the one projection whose SELECT can be prepared against the snapshot's schema. */
internal fun matchSqliteProjection(
    queries: Map<String, String>,
    canRead: (String) -> Boolean,
): Pair<String, String>? {
    val matches =
        queries.mapNotNull { (name, query) ->
            val sql = "SELECT * FROM (\n${query.trim().removeSuffix(";")}\n)"
            if (canRead("$sql LIMIT 0")) name to sql else null
        }
    require(matches.size <= 1) { "Multiple SQLite strategies read this file. Set a filename pattern: ${matches.joinToString { it.first }}" }
    return matches.singleOrNull()
}

internal fun isSchemaMismatch(message: String?): Boolean =
    message?.contains("no such table") == true || message?.contains("no such column") == true

/** Executes a sample of the editable SELECT against the stored file. */
suspend fun previewSqliteQuery(
    bytes: ByteArray,
    query: String,
): CsvParseResult = readTable(bytes, "SELECT * FROM (\n${query.trim().removeSuffix(";")}\n) LIMIT 50")

private suspend fun readTable(
    bytes: ByteArray,
    query: String,
): CsvParseResult =
    withContext(ioDispatcher) {
        querySqlite(bytes, mapOf("Table" to query))?.second ?: throw SqliteReadException("Cannot read the requested table")
    }

suspend fun sqliteTables(bytes: ByteArray): List<String> =
    readTable(bytes, "SELECT name FROM main.sqlite_master WHERE type='table' AND name NOT GLOB 'sqlite_*' ORDER BY name").rows.map {
        it.single()
    }

private fun identifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

/** Reads one page of the raw table, optionally restricted to the key identified by a strategy's source reference. */
suspend fun sqliteTablePage(
    bytes: ByteArray,
    table: String,
    offset: Int,
    limit: Int,
    ref: SqliteRowRef? = null,
): CsvParseResult {
    require(offset >= 0 && limit > 0 && (ref == null || ref.table == table))
    val predicate =
        ref
            ?.key
            ?.entries
            ?.joinToString(" AND ", prefix = " WHERE ") { (column, value) ->
                "record.${identifier(column)} = '" + value.replace("'", "''") + "'"
            }.orEmpty()
    return readTable(bytes, "SELECT * FROM main.${identifier(table)} AS record$predicate LIMIT $limit OFFSET $offset")
}
