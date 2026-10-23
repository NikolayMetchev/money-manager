@file:OptIn(ExperimentalWasmJsInterop::class)

package com.moneymanager.test.database

import com.moneymanager.database.SqliteChannel
import com.moneymanager.database.SqliteException
import com.moneymanager.database.SqliteRequest
import com.moneymanager.database.SqliteResponse
import com.moneymanager.test.database.memory.MemoryHandler
import com.moneymanager.test.database.memory.createMemoryHandler
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop

/**
 * The browser targets' [SqliteChannel]: the database worker's own handler, run in this process over in-memory
 * databases, so the tests exercise the same protocol the browser does without a worker or OPFS (Node has neither).
 * One handler serves the whole test run, as one worker serves the browser app.
 */
internal object MemorySqliteChannel : SqliteChannel {
    private var handler: MemoryHandler? = null
    private var nextId = 0

    override suspend fun send(request: SqliteRequest): SqliteResponse {
        val handler = handler ?: createMemoryHandler().await<MemoryHandler>().also { handler = it }
        request.id = nextId++
        val response = handler.handle(request).await<SqliteResponse>()
        response.error?.let { throw SqliteException(it) }
        return response
    }

    // The handler does everything but a restore before its promise first waits, so this has happened on return.
    override fun post(request: SqliteRequest) {
        request.id = nextId++
        checkNotNull(handler) { "Nothing was sent before ${request.type}" }.handle(request)
    }

    override fun close() = Unit
}
