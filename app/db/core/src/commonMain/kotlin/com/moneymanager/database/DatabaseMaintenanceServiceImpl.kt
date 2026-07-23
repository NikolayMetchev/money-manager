package com.moneymanager.database

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.measureTime

/**
 * Implementation of [DatabaseMaintenanceService] that executes SQLite maintenance commands
 * using SQLDelight-generated queries.
 *
 * Balances are recomputed in Kotlin using arbitrary-precision [BigInteger] arithmetic: amounts are
 * stored as decimal-string TEXT (a wallet can hold e.g. 18-decimal crypto whose value overflows a
 * Long), and SQLite cannot SUM TEXT without losing precision, so the aggregation cannot live in SQL.
 * Reads stay fast because balances are materialized here and only recomputed on refresh.
 */
class DatabaseMaintenanceServiceImpl(
    database: MoneyManagerDatabaseWrapper,
) : DatabaseMaintenanceService {
    private val maintenanceWriteQueries = database.maintenanceWriteQueries
    private val transferWriteQueries = database.transferWriteQueries
    private val balanceLegsQueries = database.balanceLegsSelectQueries

    override suspend fun reindex(): Duration =
        withContext(Dispatchers.Default) {
            measureTime {
                maintenanceWriteQueries.reindex()
            }
        }

    override suspend fun vacuum(): Duration =
        withContext(Dispatchers.Default) {
            measureTime {
                maintenanceWriteQueries.vacuum()
            }
        }

    override suspend fun analyze(): Duration =
        withContext(Dispatchers.Default) {
            measureTime {
                maintenanceWriteQueries.analyze()
            }
        }

    // NOTE: the `pending_materialized_view_changes` dirty-tracking is retained (triggers still fill it)
    // but both refresh entry points currently do a full Kotlin recompute for correctness/simplicity;
    // scoping the recompute to only the dirty (account, asset) pairs is a future optimization.
    override suspend fun refreshMaterializedViews(): Duration =
        withContext(Dispatchers.Default) {
            measureTime { recompute() }
        }

    override suspend fun truncateMaterializedViews(): Duration =
        withContext(Dispatchers.Default) {
            measureTime {
                transferWriteQueries.transaction {
                    transferWriteQueries.deleteAllAccountBalances()
                    transferWriteQueries.deleteAllRunningBalances()
                    transferWriteQueries.clearPendingChanges()
                }
            }
        }

    override suspend fun fullRefreshMaterializedViews(): Duration =
        withContext(Dispatchers.Default) {
            measureTime { recompute() }
        }

    /**
     * Rebuilds both materialized views from scratch by enumerating every transaction leg
     * (transfer + trade) and aggregating in Kotlin BigInteger.
     */
    private fun recompute() {
        val legs = balanceLegsQueries.selectAllTransactionLegs().executeAsList()
        transferWriteQueries.transaction {
            // Account balances: sum signed amounts of non-excluded legs, per (account, asset).
            transferWriteQueries.deleteAllAccountBalances()
            val balances = LinkedHashMap<Pair<Long, Long>, BigInteger>()
            for (leg in legs) {
                if (leg.is_excluded != 0L) continue
                val key = leg.account_id to leg.asset_id
                balances[key] = (balances[key] ?: BigInteger.ZERO) + signedAmount(leg.amount, leg.sign)
            }
            for ((key, balance) in balances) {
                transferWriteQueries.insertAccountBalance(key.first, key.second, balance.toString())
            }

            // Running balances: per (account, asset), ordered by (timestamp, id), cumulative sum of
            // non-excluded amounts. Excluded rows are listed but do not move the running balance.
            transferWriteQueries.deleteAllRunningBalances()
            val byAccountAsset = legs.groupBy { it.account_id to it.asset_id }
            for ((key, rows) in byAccountAsset) {
                var running = BigInteger.ZERO
                rows.sortedWith(compareBy({ it.timestamp }, { it.id })).forEach { row ->
                    val signed = signedAmount(row.amount, row.sign)
                    if (row.is_excluded == 0L) running += signed
                    transferWriteQueries.insertRunningBalance(
                        id = row.id,
                        timestamp = row.timestamp,
                        description = row.description,
                        account_id = key.first,
                        asset_id = key.second,
                        transaction_amount = signed.toString(),
                        running_balance = running.toString(),
                        is_excluded = row.is_excluded,
                    )
                }
            }

            transferWriteQueries.clearPendingChanges()
        }
    }

    private fun signedAmount(
        amount: String,
        sign: Long,
    ): BigInteger {
        val value = BigInteger(amount)
        return if (sign < 0) -value else value
    }
}
