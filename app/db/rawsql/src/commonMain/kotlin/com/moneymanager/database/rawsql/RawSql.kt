package com.moneymanager.database.rawsql

import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * The one place hand-written SQL runs. Every other query is a SQLDelight `.sq` query, so a statement only comes
 * here when SQLDelight cannot express it:
 *  - SQL a person types (`mm sql`),
 *  - the per-import CSV staging tables, whose name and column count are chosen at runtime,
 *  - connection PRAGMAs, which are driver setup,
 *  - `dbstat` and per-table row counts for the database size breakdown.
 */
class RawSql(
    private val driver: SqlDriver,
) {
    /** Executes a statement, returning the number of rows it changed. */
    suspend fun execute(
        sql: String,
        parameters: Int = 0,
        binders: (SqlPreparedStatement.() -> Unit)? = null,
    ): Long = driver.execute(null, sql, parameters, binders).await()

    /** Executes a statement whose parameters are all strings, bound in order. */
    suspend fun executeWithStrings(
        sql: String,
        parameters: List<String>,
    ) {
        execute(sql, parameters.size) {
            parameters.forEachIndexed { index, value -> bindString(index, value) }
        }
    }

    /**
     * A query over hand-written SQL, mapping each row with [mapper]. Run it with SQLDelight's
     * `awaitAsList`/`awaitAsOne`, which read the cursor whether the driver is blocking or not.
     */
    fun <T : Any> query(
        sql: String,
        parameters: Int = 0,
        binders: (SqlPreparedStatement.() -> Unit)? = null,
        mapper: (SqlCursor) -> T,
    ): ExecutableQuery<T> =
        object : ExecutableQuery<T>(mapper) {
            override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
                driver.executeQuery(null, sql, mapper, parameters, binders)
        }
}
