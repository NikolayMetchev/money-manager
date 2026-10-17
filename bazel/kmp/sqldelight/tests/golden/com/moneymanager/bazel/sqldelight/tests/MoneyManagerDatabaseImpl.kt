@file:Suppress("REDUNDANT_VISIBILITY_MODIFIER", "ASSIGNED_VALUE_IS_NEVER_READ")

package com.moneymanager.bazel.sqldelight.tests

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.moneymanager.bazel.sqldelight.LedgerQueries
import com.moneymanager.bazel.sqldelight.MoneyManagerDatabase
import kotlin.Long
import kotlin.Unit
import kotlin.reflect.KClass

internal val KClass<MoneyManagerDatabase>.schema: SqlSchema<QueryResult.Value<Unit>>
  get() = MoneyManagerDatabaseImpl.Schema

internal fun KClass<MoneyManagerDatabase>.newInstance(driver: SqlDriver): MoneyManagerDatabase = MoneyManagerDatabaseImpl(driver)

private class MoneyManagerDatabaseImpl(
  driver: SqlDriver,
) : TransacterImpl(driver),
    MoneyManagerDatabase {
  override val ledgerQueries: LedgerQueries = LedgerQueries(driver)

  public object Schema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long
      get() = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
      driver.execute(null, """
          |CREATE TABLE ledger (
          |    id INTEGER NOT NULL PRIMARY KEY,
          |    name TEXT NOT NULL,
          |    balance INTEGER NOT NULL DEFAULT 0
          |)
          """.trimMargin(), 0)
      return QueryResult.Unit
    }

    override fun migrate(
      driver: SqlDriver,
      oldVersion: Long,
      newVersion: Long,
      vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Unit
  }
}
