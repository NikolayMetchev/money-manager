@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database

import com.moneymanager.database.sql.MoneyManagerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.measureTime

/**
 * Implementation of [DatabaseMaintenanceService] that executes SQLite maintenance commands
 * using SQLDelight-generated queries.
 */
class DatabaseMaintenanceServiceImpl(
    database: MoneyManagerDatabase,
) : DatabaseMaintenanceService {
    private val maintenanceWriteQueries = database.maintenanceWriteQueries
    private val transferWriteQueries = database.transferWriteQueries

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

    override suspend fun refreshMaterializedViews(): Duration =
        withContext(Dispatchers.Default) {
            measureTime {
                transferWriteQueries.transaction {
                    // Incremental refresh - only update affected account-currency pairs
                    transferWriteQueries.incrementalRefreshAccountBalances()
                    transferWriteQueries.incrementalPopulateAccountBalances()
                    transferWriteQueries.incrementalRefreshRunningBalances()
                    transferWriteQueries.incrementalPopulateRunningBalances()
                    // Clear pending changes after both views are refreshed
                    transferWriteQueries.clearPendingChanges()
                }
            }
        }

    override suspend fun truncateMaterializedViews(): Duration =
        withContext(Dispatchers.Default) {
            measureTime {
                transferWriteQueries.transaction {
                    // Reuse the existing full-refresh DELETE statements, without repopulating.
                    transferWriteQueries.refreshAccountBalances()
                    transferWriteQueries.refreshRunningBalances()
                    transferWriteQueries.clearPendingChanges()
                }
            }
        }

    override suspend fun fullRefreshMaterializedViews(): Duration =
        withContext(Dispatchers.Default) {
            measureTime {
                transferWriteQueries.transaction {
                    // Full refresh - delete and rebuild all data
                    transferWriteQueries.refreshAccountBalances()
                    transferWriteQueries.populateAccountBalances()
                    transferWriteQueries.refreshRunningBalances()
                    transferWriteQueries.populateRunningBalances()
                    // Clear pending changes to avoid redundant work
                    transferWriteQueries.clearPendingChanges()
                }
            }
        }
}
