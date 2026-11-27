package com.moneymanager.csvimporter

import com.moneymanager.csv.CsvParseResult
import org.sqlite.Function
import org.sqlite.JDBC
import java.io.IOException
import java.sql.SQLException
import java.util.Properties

private const val QUERY_TIMEOUT_SECONDS = 15

internal actual fun querySqlite(
    bytes: ByteArray,
    queries: Map<String, String>,
): Pair<String, CsvParseResult>? =
    try {
        readSnapshot(bytes, queries)
    } catch (failure: SQLException) {
        throw SqliteReadException(failure.message ?: "Could not query the SQLite database", failure)
    } catch (failure: IOException) {
        throw SqliteReadException(failure.message ?: "Could not open the SQLite snapshot", failure)
    }

private fun readSnapshot(
    bytes: ByteArray,
    queries: Map<String, String>,
): Pair<String, CsvParseResult>? =
    withSqliteSnapshot(bytes) { snapshot ->
        val url = "jdbc:sqlite:${snapshot.toUri()}?mode=ro&immutable=1"
        JDBC.createConnection(url, Properties()).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA trusted_schema=OFF")
                statement.execute("PRAGMA query_only=ON")
            }
            sqliteProjectionFunctions.forEach { (name, operation) ->
                Function.create(connection, name, SqliteScalar(operation), 1, Function.FLAG_DETERMINISTIC)
            }
            // Preparing a subquery admits a row-producing SELECT, including one with a WITH clause.
            // Force the header/schema read even when no strategy has been installed.
            connection.createStatement().use { it.executeQuery("SELECT count(*) FROM sqlite_master").close() }
            val (name, sql) =
                matchSqliteProjection(queries) { sql ->
                    try {
                        connection.prepareStatement(sql).use { it.executeQuery().close() }
                        true
                    } catch (failure: SQLException) {
                        if (!isSchemaMismatch(failure.message)) throw failure
                        false
                    }
                } ?: return@use null

            connection.prepareStatement(sql).use { statement ->
                statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                statement.executeQuery().use { result ->
                    val columns = 1..result.metaData.columnCount
                    name to
                        CsvParseResult(
                            headers = columns.map(result.metaData::getColumnLabel),
                            rows =
                                buildList {
                                    while (result.next()) add(columns.map { result.getString(it).orEmpty() })
                                },
                        )
                }
            }
        }
    }

private class SqliteScalar(
    private val operation: (String) -> String,
) : Function() {
    override fun xFunc() {
        result(value_text(0)?.let(operation))
    }
}
