@file:JsModule("@moneymanager/sqlite-worker/memory")
@file:OptIn(ExperimentalWasmJsInterop::class)

package com.moneymanager.test.database.memory

import com.moneymanager.database.SqliteRequest
import com.moneymanager.database.SqliteResponse
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise

/** The database worker's handler over in-memory databases (app/db/worker's memory.mjs), in this process. */
external fun createMemoryHandler(): Promise<MemoryHandler>

external interface MemoryHandler : JsAny {
    fun handle(request: SqliteRequest): Promise<SqliteResponse>
}
