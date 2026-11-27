package com.moneymanager.csvimporter

import com.moneymanager.csv.CsvParseResult
import org.sqlite.Function
import org.sqlite.JDBC
import java.io.IOException
import java.math.RoundingMode
import java.nio.file.Files
import java.sql.Connection
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
): Pair<String, CsvParseResult>? {
    val snapshot = Files.createTempFile("mm-import-", ".sqlite")
    try {
        Files.write(snapshot, bytes)
        val url = "jdbc:sqlite:${snapshot.toUri()}?mode=ro&immutable=1"
        return JDBC.createConnection(url, Properties()).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA trusted_schema=OFF")
                statement.execute("PRAGMA query_only=ON")
            }
            Function.create(connection, "decimal_ratio", DecimalRatio(), 2, Function.FLAG_DETERMINISTIC)
            // Preparing a subquery admits a row-producing SELECT, including one with a WITH clause.
            // Force the header/schema read even when no strategy has been installed.
            connection.createStatement().use { it.executeQuery("SELECT count(*) FROM sqlite_schema").close() }
            val (name, sql) = connection.matchProjection(queries) ?: return null

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
    } finally {
        Files.deleteIfExists(snapshot)
    }
}

/** Prepare each candidate against this schema without scanning its rows. Report schema errors to the editor. */
private fun Connection.matchProjection(queries: Map<String, String>): Pair<String, String>? {
    val matches =
        queries.mapNotNull { (name, query) ->
            val sql = "SELECT * FROM (\n${query.trim().removeSuffix(";")}\n)"
            try {
                prepareStatement("$sql LIMIT 0").use { it.executeQuery().close() }
                name to sql
            } catch (error: SQLException) {
                // A different ledger schema is expected. Surface invalid SQL and damaged databases.
                if (error.message?.contains("no such table") != true && error.message?.contains("no such column") != true) throw error
                null
            }
        }
    if (matches.isEmpty()) return null
    require(matches.size == 1) { "Multiple SQLite strategies read this file. Set a filename pattern: ${matches.joinToString { it.first }}" }
    return matches.single()
}

/** SQLite REAL cannot represent arbitrary minor-unit integers. Divide their text values at ledger precision. */
private class DecimalRatio : Function() {
    override fun xFunc() {
        val numerator = value_text(0).toBigDecimal()
        val denominator = value_text(1).toBigDecimal()
        result(numerator.divide(denominator, 18, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString())
    }
}
