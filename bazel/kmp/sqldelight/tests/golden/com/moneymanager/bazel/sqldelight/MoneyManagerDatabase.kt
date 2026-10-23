@file:Suppress("REDUNDANT_VISIBILITY_MODIFIER", "ASSIGNED_VALUE_IS_NEVER_READ")

package com.moneymanager.bazel.sqldelight

import app.cash.sqldelight.SuspendingTransacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.moneymanager.bazel.sqldelight.tests.newInstance
import com.moneymanager.bazel.sqldelight.tests.schema
import kotlin.String
import kotlin.Unit
import kotlin.collections.List

public interface MoneyManagerDatabase : SuspendingTransacter {
  public val ledgerQueries: LedgerQueries

  public companion object {
    public val Schema: SqlSchema<QueryResult.AsyncValue<Unit>>
      get() = MoneyManagerDatabase::class.schema

    public operator fun invoke(driver: SqlDriver): MoneyManagerDatabase = MoneyManagerDatabase::class.newInstance(driver)

    public fun allTableNames(): List<String> = listOf("ledger")
  }
}
