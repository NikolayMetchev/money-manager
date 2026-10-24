package com.moneymanager.query

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** What a person's SQL returned: the column names, then each row's values in column order. */
@Serializable
data class SqlResultJson(
    val columns: List<String>,
    /** Integers are exact JSON numbers (amounts are the raw scaled values), blobs are hex strings. */
    val rows: List<List<JsonElement>>,
    /** True when the statement had more rows than the limit asked for. */
    val truncated: Boolean,
)

/** The SQL given isn't one read-only statement, or SQLite rejected it; the message says why. */
class InvalidSqlException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * Runs one SELECT (or `WITH … SELECT`, or `VALUES`) and returns at most [limit] rows (all of them when null).
 *
 * SQLite itself keeps it read-only: the statement becomes the body of a temporary view, which its grammar
 * only accepts as a select, and the whole run is a transaction that is always rolled back. The view is also
 * how the column names are found, as SQLDelight's cursor has none. The transaction keeps it all on one
 * connection: the JVM driver closes its connection after each statement otherwise, taking the view with it.
 */
suspend fun MoneyManagerDatabaseWrapper.runReadOnlySql(
    sql: String,
    limit: Int? = null,
): SqlResultJson {
    require(limit == null || limit > 0) { "limit must be positive" }
    val statement = singleStatement(sql)
    return try {
        transactionWithResult {
            val result = queryThroughView(statement, limit)
            rollback(result)
        }
    } catch (expectedFailure: Exception) {
        throw when (expectedFailure) {
            is InvalidSqlException, is CancellationException -> expectedFailure

            // The drivers' own SQLite exceptions (JDBC's, SQLiter's) have no common type.
            else -> InvalidSqlException(expectedFailure.message ?: expectedFailure.toString(), expectedFailure)
        }
    }
}

private suspend fun MoneyManagerDatabaseWrapper.queryThroughView(
    statement: String,
    limit: Int?,
): SqlResultJson {
    try {
        execute(null, "CREATE TEMP VIEW $VIEW AS $statement", 0)
    } catch (expectedRejection: Exception) {
        throw InvalidSqlException(
            "SQLite refused the statement (only a single SELECT is allowed): ${expectedRejection.message}",
            expectedRejection,
        )
    }
    val columns =
        rawQuery("SELECT name FROM pragma_table_info('$VIEW', 'temp') ORDER BY cid") { checkNotNull(it.getString(0)) }
            .awaitAsList()
    // Each value comes with its storage class, so it's read as what it is rather than as the column's affinity.
    val cells =
        columns.joinToString(", ") { name ->
            val column = quoteIdentifier(name)
            "typeof($column), CASE WHEN typeof($column) = 'blob' THEN hex($column) ELSE $column END"
        }
    val bound = limit?.let { " LIMIT ${it + 1}" }.orEmpty()
    val rows =
        rawQuery("SELECT $cells FROM temp.$VIEW$bound") { cursor ->
            List(columns.size) { index ->
                val value = 2 * index + 1
                when (cursor.getString(value - 1)) {
                    "integer" -> JsonPrimitive(cursor.getLong(value))
                    "real" -> JsonPrimitive(cursor.getDouble(value))
                    "null" -> JsonNull
                    else -> JsonPrimitive(cursor.getString(value))
                }
            }
        }.awaitAsList()
    val truncated = limit != null && rows.size > limit
    return SqlResultJson(columns, if (truncated) rows.take(limit) else rows, truncated)
}

private const val VIEW = "mm_read_only_sql"

private fun quoteIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

/**
 * [sql] without its trailing `;`, or an [InvalidSqlException] when a `;` is followed by another statement.
 * The drivers would run, or silently drop, whatever follows the first statement, so it's refused up front.
 * Quotes and comments are skipped, so a `;` inside them is just text.
 */
internal fun singleStatement(sql: String): String {
    // Past whatever was found at [found] (its [length] characters), or the end when it wasn't found.
    fun after(
        found: Int,
        length: Int,
    ): Int = if (found < 0) sql.length else found + length
    var index = 0
    var end = -1
    while (index < sql.length) {
        val char = sql[index]
        index =
            when {
                sql.startsWith("--", index) -> {
                    after(sql.indexOf('\n', index), 1)
                }

                sql.startsWith("/*", index) -> {
                    after(sql.indexOf("*/", index + 2), 2)
                }

                // Empty statements after the first are harmless; anything else is a second statement.
                end >= 0 && !char.isWhitespace() && char != ';' -> {
                    throw InvalidSqlException("Only one statement is allowed")
                }

                char == ';' -> {
                    if (end < 0) end = index
                    index + 1
                }

                // A doubled quote is an escaped one: the next pass reopens it.
                char == '\'' || char == '"' || char == '`' -> {
                    after(sql.indexOf(char, index + 1), 1)
                }

                char == '[' -> {
                    after(sql.indexOf(']', index + 1), 1)
                }

                else -> {
                    index + 1
                }
            }
    }
    val statement = (if (end < 0) sql else sql.substring(0, end)).trim()
    if (statement.isEmpty()) throw InvalidSqlException("No SQL statement given")
    return statement
}
