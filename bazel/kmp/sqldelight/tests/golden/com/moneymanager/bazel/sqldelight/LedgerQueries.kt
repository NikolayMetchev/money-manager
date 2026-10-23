@file:Suppress("REDUNDANT_VISIBILITY_MODIFIER", "ASSIGNED_VALUE_IS_NEVER_READ")

package com.moneymanager.bazel.sqldelight

import app.cash.sqldelight.Query
import app.cash.sqldelight.SuspendingTransacterImpl
import app.cash.sqldelight.db.SqlDriver
import kotlin.Any
import kotlin.Long
import kotlin.String

public class LedgerQueries(
  driver: SqlDriver,
) : SuspendingTransacterImpl(driver) {
  public fun <T : Any> selectAll(mapper: (
    id: Long,
    name: String,
    balance: Long,
  ) -> T): Query<T> = Query(1_040_948_603, arrayOf("ledger"), driver, "Ledger.sq", "selectAll", "SELECT ledger.id, ledger.name, ledger.balance FROM ledger ORDER BY name") { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getLong(2)!!
    )
  }

  public fun selectAll(): Query<Ledger> = selectAll(::Ledger)

  /**
   * @return The number of rows updated.
   */
  public suspend fun insert(name: String, balance: Long): Long {
    val result = driver.execute(613_090_307, """INSERT INTO ledger (name, balance) VALUES (?, ?)""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, name)
          bindLong(parameterIndex++, balance)
        }.await()
    notifyQueries(613_090_307) { emit ->
      emit("ledger")
    }
    return result
  }
}
