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
 * Connections are pooled rather than opened per call. A connection's first statement parses the whole
 * schema, and every staged CSV file adds a table to it: on a database with ~475 of them, opening a
 * connection and running one query took ~7.6 ms against ~0.007 ms on an open one, and SQLDelight's own
 * `JdbcSqliteDriver` (which this mirrors otherwise) closes the connection after every query outside a
 * transaction. A transaction keeps its connection until it ends, then hands it back. Every connection
 * opened is kept until [close], so the pool settles at the most queries that ran at once, which the
 * dispatchers bound (each costs ~1 MB, half of it the parsed schema). An idle connection holds no statement
 * open, so it holds no read snapshot or lock. That class is final, so this is a sibling rather than a subclass.
 */
class CachingJdbcSqliteDriver(
    private val url: String,
    properties: Properties = Properties(),
) : JdbcDriver() {
    // After every INSERT sqlite-jdbc runs its own `SELECT last_insert_rowid()` for getGeneratedKeys, which
    // SQLDelight never calls, and keeps that query open until the statement runs again. A cached INSERT
    // would leave its pooled connection holding an open query, which blocks VACUUM ("SQL statements in
    // progress") and pins a read snapshot.
    private val properties =
        Properties().apply {
            putAll(properties)
            put("jdbc.get_generated_keys", "false")
        }
    private val transactions = ThreadLocal<Transaction>()
    private val idleConnections = ArrayDeque<Connection>()
    private var closed = false
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

    override fun getConnection(): Connection =
        synchronized(idleConnections) { idleConnections.removeLastOrNull() } ?: DriverManager.getConnection(url, properties)

    override fun closeConnection(connection: Connection) {
        // A transaction's connection goes back only when the transaction ends (the setter above).
        if (transaction != null) return
        val pooled = synchronized(idleConnections) { (!closed).also { if (it) idleConnections.addLast(connection) } }
        if (!pooled) discard(connection)
    }

    /** Closes the idle connections; a connection still in use is closed when it is handed back. */
    override fun close() {
        val idle =
            synchronized(idleConnections) {
                closed = true
                idleConnections.toList().also { idleConnections.clear() }
            }
        idle.forEach(::discard)
    }

    private fun discard(connection: Connection) {
        statementCaches.remove(connection)?.values?.forEach { it.statement.close() }
        connection.close()
    }

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
