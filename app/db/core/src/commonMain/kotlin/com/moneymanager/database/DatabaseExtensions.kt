package com.moneymanager.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.moneymanager.database.sql.MoneyManagerDatabase

/**
 * Access the underlying SqlDriver from MoneyManagerDatabase.
 * This uses reflection to access the protected driver field from TransacterImpl.
 */
private val MoneyManagerDatabase.driver: SqlDriver
    get() {
        val field = this::class.java.superclass.getDeclaredField("driver")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(this) as SqlDriver
    }

/**
 * Execute a SQL statement on the database.
 * Delegates to the underlying SqlDriver.
 */
fun MoneyManagerDatabase.execute(
    identifier: Int?,
    sql: String,
    parameters: Int,
) = driver.execute(identifier, sql, parameters)

/**
 * Execute a SQL query on the database.
 * Delegates to the underlying SqlDriver.
 */
fun <R> MoneyManagerDatabase.executeQuery(
    identifier: Int?,
    sql: String,
    mapper: (SqlDriver.SqlCursor) -> QueryResult<R>,
    parameters: Int,
): QueryResult<R> = driver.executeQuery(identifier, sql, mapper, parameters)
