@file:OptIn(ExperimentalWasmJsInterop::class)

package com.moneymanager.database

import org.khronos.webgl.Int8Array
import kotlin.js.ExperimentalWasmJsInterop

/**
 * The way to the browser's SQLite (`app/db/worker`): one request, one response. [WorkerSqliteChannel] posts them to
 * the database worker; the browser targets' tests answer them in-process over memory.
 */
interface SqliteChannel {
    /** Sends [request] and returns its response, or throws [SqliteException] with the error it reports. */
    suspend fun send(request: SqliteRequest): SqliteResponse

    /** Sends [request] without waiting for its response, after every request sent before it. */
    fun post(request: SqliteRequest)

    /** Ends the channel: the worker stops, with every database it has open. */
    fun close()
}

/** What the worker could not do, with its message (SQLite's, for a failed statement). */
class SqliteException(
    message: String,
) : RuntimeException(message)

/** A request to the worker (handler.mjs documents each `type`). */
external interface SqliteRequest : JsAny {
    var id: Int
    var type: String
    var db: String?
    var sql: String?
    var params: JsArray<JsAny?>?

    /** One letter per parameter: `n` null, `i` a 64-bit integer as decimal text, `r` a number, `t` text, `b` bytes. */
    var types: String?
    var bytes: Int8Array?
}

/** The worker's answer to the [SqliteRequest] with the same [id]. */
external interface SqliteResponse : JsAny {
    val id: Int
    val error: String?

    /** Every value is null, text (an INTEGER as decimal text, a REAL in its shortest form) or an [Int8Array]. */
    val rows: JsArray<JsArray<JsAny?>>?
    val changes: Double?
    val exists: Boolean?
    val size: Double?
    val bytes: Int8Array?
}

fun sqliteRequest(
    type: String,
    db: String?,
    configure: SqliteRequest.() -> Unit = {},
): SqliteRequest =
    newObject<SqliteRequest>().apply {
        this.type = type
        this.db = db
        configure()
    }

private fun <T : JsAny> newObject(): T = js("({})")
