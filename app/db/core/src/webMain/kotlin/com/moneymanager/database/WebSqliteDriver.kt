@file:OptIn(ExperimentalWasmJsInterop::class)

package com.moneymanager.database

import app.cash.sqldelight.Query
import app.cash.sqldelight.SuspendingTransacter
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toByteArray
import org.khronos.webgl.toInt8Array
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.js.ExperimentalWasmJsInterop

/**
 * A [SqlDriver] for database [name] in the browser's SQLite worker, over [channel]. Every statement is a message, so
 * every result is a [QueryResult.AsyncValue] (the schema is generated with `generateAsync`).
 *
 * The worker has one connection per database, so a transaction is that connection's: while one is open, every
 * write runs inside it. Writes take turns instead: a transaction holds [writeTurn] for its whole length (through
 * [dispatch], which SQLDelight's suspending transactions call), and a write outside one takes it for its statement.
 * The coroutines inside a transaction carry [InTransaction], so its nested transactions and statements don't wait
 * for themselves. Reads never wait, so they keep working during a long import, and see its uncommitted rows.
 *
 * 64-bit integers cross as decimal text (see handler.mjs), never as a JavaScript number.
 */
class WebSqliteDriver internal constructor(
    private val channel: SqliteChannel,
    internal val name: String,
) : SqlDriver,
    SuspendingTransacter.TransactionDispatcher {
    private val listeners = mutableMapOf<String, MutableSet<Query.Listener>>()
    private val writeTurn = Mutex()
    private var transaction: Transacter.Transaction? = null

    private inner class InTransaction : AbstractCoroutineContextElement(Key) {
        val driver = this@WebSqliteDriver
    }

    private object Key : CoroutineContext.Key<InTransaction>

    private suspend fun inTransaction() = currentCoroutineContext()[Key]?.driver === this

    override suspend fun <R> dispatch(transaction: suspend () -> R): R =
        if (inTransaction()) {
            transaction()
        } else {
            writeTurn.withLock { withContext(InTransaction()) { transaction() } }
        }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        val statement = WebPreparedStatement().apply { binders?.invoke(this) }
        return QueryResult.AsyncValue {
            val rows = exec(sql, statement).rows
            mapper(WebCursor(checkNotNull(rows) { "No rows for: $sql" })).await()
        }
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        val statement = WebPreparedStatement().apply { binders?.invoke(this) }
        return QueryResult.AsyncValue {
            val response =
                if (inTransaction()) {
                    exec(sql, statement)
                } else {
                    writeTurn.withLock { exec(sql, statement) }
                }
            response.changes?.toLong() ?: 0L
        }
    }

    private suspend fun exec(
        sql: String,
        statement: WebPreparedStatement? = null,
    ): SqliteResponse =
        channel.send(
            sqliteRequest("exec", name) {
                this.sql = sql
                statement?.let { (values, types) ->
                    params = values
                    this.types = types
                }
            },
        )

    override fun newTransaction(): QueryResult<Transacter.Transaction> =
        QueryResult.AsyncValue {
            val enclosing = transaction
            val transaction = Transaction(enclosing)
            this.transaction = transaction
            if (enclosing == null) exec("BEGIN")
            transaction
        }

    override fun currentTransaction(): Transacter.Transaction? = transaction

    private inner class Transaction(
        override val enclosingTransaction: Transacter.Transaction?,
    ) : Transacter.Transaction() {
        override fun endTransaction(successful: Boolean): QueryResult<Unit> =
            QueryResult.AsyncValue {
                try {
                    if (enclosingTransaction == null) exec(if (successful) "COMMIT" else "ROLLBACK")
                } finally {
                    transaction = enclosingTransaction
                }
            }
    }

    override fun addListener(
        vararg queryKeys: String,
        listener: Query.Listener,
    ) {
        queryKeys.forEach { listeners.getOrPut(it) { mutableSetOf() }.add(listener) }
    }

    override fun removeListener(
        vararg queryKeys: String,
        listener: Query.Listener,
    ) {
        queryKeys.forEach { listeners[it]?.remove(listener) }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        queryKeys
            .flatMap { listeners[it].orEmpty() }
            .distinct()
            .forEach(Query.Listener::queryResultsChanged)
    }

    /** Closes the database in the worker, after the requests already sent; the worker stays up for the next one. */
    override fun close() {
        channel.post(sqliteRequest("close", name))
    }
}

/** Parameters by index, sent as values plus their type letters. */
private class WebPreparedStatement : SqlPreparedStatement {
    private val values = mutableMapOf<Int, Pair<JsAny?, Char>>()

    override fun bindBytes(
        index: Int,
        bytes: ByteArray?,
    ) {
        values[index] = bytes?.let { it.toInt8Array() to 'b' } ?: NULL
    }

    override fun bindLong(
        index: Int,
        long: Long?,
    ) {
        values[index] = long?.let { it.toString().toJsString() to 'i' } ?: NULL
    }

    override fun bindDouble(
        index: Int,
        double: Double?,
    ) {
        values[index] = double?.let { it.toJsNumber() to 'r' } ?: NULL
    }

    override fun bindString(
        index: Int,
        string: String?,
    ) {
        values[index] = string?.let { it.toJsString() to 't' } ?: NULL
    }

    override fun bindBoolean(
        index: Int,
        boolean: Boolean?,
    ) {
        bindLong(index, boolean?.let { if (it) 1L else 0L })
    }

    operator fun component1(): JsArray<JsAny?> {
        val array = JsArray<JsAny?>()
        for (index in 0 until count()) array[index] = values[index]?.first
        return array
    }

    operator fun component2(): String = (0 until count()).map { values[it]?.second ?: 'n' }.joinToString("")

    private fun count() = (values.keys.maxOrNull() ?: -1) + 1

    private companion object {
        val NULL = null to 'n'
    }
}

private class WebCursor(
    private val rows: JsArray<JsArray<JsAny?>>,
) : SqlCursor {
    private var row = -1

    override fun next(): QueryResult<Boolean> = QueryResult.Value(++row < rows.length)

    private fun value(index: Int): JsAny? = checkNotNull(rows[row])[index]

    // A JsString on wasm, a String on js: toString() reads either.
    override fun getString(index: Int): String? = value(index)?.toString()

    override fun getLong(index: Int): Long? = getString(index)?.toLong()

    override fun getDouble(index: Int): Double? = getString(index)?.toDouble()

    override fun getBoolean(index: Int): Boolean? = getLong(index)?.let { it != 0L }

    override fun getBytes(index: Int): ByteArray? = value(index)?.unsafeCast<Int8Array>()?.toByteArray()
}
