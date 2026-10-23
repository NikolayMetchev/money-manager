@file:OptIn(ExperimentalWasmJsInterop::class)

package com.moneymanager.database

import kotlinx.coroutines.CompletableDeferred
import org.w3c.dom.MODULE
import org.w3c.dom.MessageEvent
import org.w3c.dom.Worker
import org.w3c.dom.WorkerOptions
import org.w3c.dom.WorkerType
import kotlin.js.ExperimentalWasmJsInterop

/**
 * A [SqliteChannel] to the database worker at [scriptUrl] (`@moneymanager/sqlite-worker/worker`), started as a
 * module worker. Responses come back by id, so requests from many coroutines can be in flight at once; the
 * worker answers them in order.
 */
class WorkerSqliteChannel(
    scriptUrl: String,
) : SqliteChannel {
    private val worker = Worker(scriptUrl, WorkerOptions(type = WorkerType.MODULE))
    private val pending = mutableMapOf<Int, CompletableDeferred<SqliteResponse>>()
    private var nextId = 0

    init {
        worker.onmessage = { event: MessageEvent ->
            val response = event.data?.unsafeCast<SqliteResponse>()
            response?.let { pending.remove(it.id)?.complete(it) }
        }
        worker.onerror = { event ->
            val failure = SqliteException("The database worker failed: ${event.type}")
            pending.values.forEach { it.completeExceptionally(failure) }
            pending.clear()
        }
    }

    override suspend fun send(request: SqliteRequest): SqliteResponse {
        val id = nextId++
        request.id = id
        val response = CompletableDeferred<SqliteResponse>()
        pending[id] = response
        worker.postMessage(request)
        return response.await().also { it.error?.let { message -> throw SqliteException(message) } }
    }

    override fun post(request: SqliteRequest) {
        request.id = nextId++
        worker.postMessage(request)
    }

    override fun close() {
        worker.terminate()
        val failure = SqliteException("The database worker was closed")
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
    }
}
