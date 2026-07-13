package com.moneymanager.database

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.ConnectionManager.Transaction
import app.cash.sqldelight.driver.jdbc.JdbcCursor
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import app.cash.sqldelight.driver.jdbc.JdbcPreparedStatement
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.getOrSet

/**
 * A file-backed SQLite driver that keeps compiled statements around instead of re-preparing them.
 *
 * SQLDelight's stock [JdbcDriver] calls `Connection.prepareStatement` on every execute and closes the
 * statement afterwards, ignoring the `identifier` it is handed for exactly this purpose (its Android
 * counterpart does cache). Since SQLite compiles the SQL on prepare, a bulk import spent more time
 * compiling its handful of insert statements than running them — profiling a sample-data import showed
 * `NativeDB.prepare` outweighing statement execution by ~1.6x.
 *
 * Statements are cached per [Connection] and keyed by SQLDelight's `identifier` (statements without one
 * — pragmas, schema DDL — are prepared and closed as before, since they run once). Closing a connection
 * closes its cached statements, so the cache cannot outlive the connection it belongs to.
 *
 * Connection handling mirrors SQLDelight's own `JdbcSqliteDriver` for file databases: one connection per
 * thread, opened on demand and closed when no transaction is in flight. That class is final, so this is a
 * sibling rather than a subclass.
 */
class CachingJdbcSqliteDriver(
    private val url: String,
    private val properties: Properties = Properties(),
) : JdbcDriver() {
    private val transactions = ThreadLocal<Transaction>()
    private val connections = ThreadLocal<Connection>()
    private val statementCaches = ConcurrentHashMap<Connection, MutableMap<Int, CachedStatement>>()
    private val listeners = linkedMapOf<String, MutableSet<Query.Listener>>()

    override var transaction: Transaction?
        get() = transactions.get()
        set(value) {
            val currentTransaction = transactions.get()
            transactions.set(value)
            if (value == null && currentTransaction != null) {
                closeConnection(currentTransaction.connection)
            }
        }

    override fun getConnection(): Connection = connections.getOrSet { DriverManager.getConnection(url, properties) }

    override fun closeConnection(connection: Connection) {
        check(connections.get() == connection) { "Connections must be closed on the thread that opened them" }
        if (transaction == null) {
            statementCaches.remove(connection)?.values?.forEach { it.statement.close() }
            connection.close()
            connections.remove()
        }
    }

    override fun close() = Unit

    // SQLite transactions are explicit statements rather than autoCommit toggling, matching JdbcSqliteDriver.
    override fun Connection.beginTransaction() {
        prepareStatement("BEGIN TRANSACTION").use(PreparedStatement::execute)
    }

    override fun Connection.endTransaction() {
        prepareStatement("END TRANSACTION").use(PreparedStatement::execute)
    }

    override fun Connection.rollbackTransaction() {
        prepareStatement("ROLLBACK TRANSACTION").use(PreparedStatement::execute)
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        val (connection, onClose) = connectionAndClose()
        try {
            return QueryResult.Value(
                withStatement(connection, identifier, sql, binders) { statement ->
                    if (statement.execute()) 0L else statement.updateCount.toLong()
                },
            )
        } finally {
            onClose()
        }
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        val (connection, onClose) = connectionAndClose()
        try {
            return withStatement(connection, identifier, sql, binders) { statement ->
                statement.executeQuery().use { resultSet -> mapper(JdbcCursor(resultSet)) }
            }
        } finally {
            onClose()
        }
    }

    /**
     * Runs [body] against a statement for [sql]: the cached one when SQLDelight gave an [identifier],
     * otherwise a throw-away statement closed on the way out.
     */
    private fun <R> withStatement(
        connection: Connection,
        identifier: Int?,
        sql: String,
        binders: (SqlPreparedStatement.() -> Unit)?,
        body: (PreparedStatement) -> R,
    ): R {
        if (identifier == null) {
            return connection.prepareStatement(sql).use { statement ->
                bind(statement, binders)
                body(statement)
            }
        }
        val cache = statementCaches.getOrPut(connection) { mutableMapOf() }
        val cached = cache[identifier]
        // SQLDelight derives the identifier from the SQL, so a hit should always be the same statement;
        // compare anyway, because a hash collision would otherwise run the wrong SQL.
        val entry =
            if (cached != null && cached.sql == sql) {
                cached
            } else {
                // Drop the stale entry first: if the re-prepare below throws, the cache must not keep
                // pointing at a statement this just closed.
                cache.remove(identifier)?.statement?.close()
                CachedStatement(sql, connection.prepareStatement(sql)).also { cache[identifier] = it }
            }
        bind(entry.statement, binders)
        return body(entry.statement)
    }

    private class CachedStatement(
        val sql: String,
        val statement: PreparedStatement,
    )

    private fun bind(
        statement: PreparedStatement,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ) {
        // A reused statement still holds the previous row's parameters; clear them so a binder that skips
        // a parameter can never silently inherit a stale value.
        statement.clearParameters()
        if (binders != null) JdbcPreparedStatement(statement).binders()
    }

    override fun addListener(
        vararg queryKeys: String,
        listener: Query.Listener,
    ) {
        synchronized(listeners) {
            queryKeys.forEach { listeners.getOrPut(it) { linkedSetOf() }.add(listener) }
        }
    }

    override fun removeListener(
        vararg queryKeys: String,
        listener: Query.Listener,
    ) {
        synchronized(listeners) {
            queryKeys.forEach { listeners[it]?.remove(listener) }
        }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val listenersToNotify = linkedSetOf<Query.Listener>()
        synchronized(listeners) {
            queryKeys.forEach { listeners[it]?.let(listenersToNotify::addAll) }
        }
        listenersToNotify.forEach(Query.Listener::queryResultsChanged)
    }
}
