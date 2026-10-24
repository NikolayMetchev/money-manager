package com.moneymanager.cli

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import com.github.ajalt.mordant.table.table
import com.moneymanager.query.InvalidSqlException
import com.moneymanager.query.SqlResultJson
import com.moneymanager.query.runReadOnlySql
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** `mm sql`: one read-only SQL statement straight against the database, for debugging. */
internal class SqlCommand : SuspendingCliktCommand(name = "sql") {
    private val runtime by requireObject<CliRuntime>()
    private val sql by argument("SQL", help = "One SELECT statement; omit it, or pass -, to read it from stdin").optional()
    private val limit by option("--limit", metavar = "N", help = "At most N rows (default $DEFAULT_LIMIT, 0 for all)")
        .int()
        .restrictTo(min = 0)
        .default(DEFAULT_LIMIT)

    override fun help(context: Context): String =
        """
        Run one read-only SQL statement (SELECT, WITH … SELECT or VALUES) against the database.

        Anything that would change the database is refused. A cloud database with no copy on this device is
        restored for the query and its copy deleted again afterwards (unless --keep-local).
        """.trimIndent()

    override suspend fun run() {
        val statement = sql?.takeUnless { it == "-" } ?: readStdin()
        val result =
            try {
                runtime.withSession(dropRestoredCopy = true) { session ->
                    session.database.runReadOnlySql(statement, limit.takeIf { it > 0 })
                }
            } catch (invalid: InvalidSqlException) {
                throw UsageError(invalid.message)
            }
        runtime.output.emit(result, SqlResultJson.serializer()) { value ->
            table {
                header { row(*value.columns.toTypedArray()) }
                body { value.rows.forEach { cells -> row(*cells.map { it.display() }.toTypedArray()) } }
            }
        }
        if (result.truncated) runtime.console.warn("Stopped at $limit rows; rerun with --limit 0 for all of them")
    }

    private fun readStdin(): String {
        if (runtime.console.vaultPasswordFromStdin) {
            throw UsageError("With --vault-password-stdin, pass the SQL as an argument")
        }
        return generateSequence { terminal.readLineOrNull(hideInput = false) }.joinToString("\n")
    }

    private fun JsonElement.display(): String =
        when (this) {
            JsonNull -> "NULL"
            is JsonPrimitive -> content
            else -> toString()
        }

    private companion object {
        const val DEFAULT_LIMIT = 1000
    }
}
