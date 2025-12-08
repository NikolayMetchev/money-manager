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
    private val maintenanceQueries = database.maintenanceQueries
    private val transferQueries = database.transferQueries

    override suspend fun reindex(): Duration =
        withContext(Dispatchers.Default) {
            measureTime {
                maintenanceQueries.reindex()
            }
        }

    override suspend fun vacuum(): Duration =
        withContext(Dispatchers.Default) {
            measureTime {
                maintenanceQueries.vacuum()
            }
        }

    override suspend fun analyze(): Duration =
        withContext(Dispatchers.Default) {
            measureTime {
                maintenanceQueries.analyze()
            }
        }

    override suspend fun refreshMaterializedViews(): Duration =
        withContext(Dispatchers.Default) {
            measureTime {
                transferQueries.transaction {
                    // Incremental refresh - only update affected account-currency pairs
                    transferQueries.incrementalRefreshAccountBalances()
                    transferQueries.incrementalPopulateAccountBalances()
                    transferQueries.incrementalRefreshRunningBalances()
                    transferQueries.incrementalPopulateRunningBalances()
                    // Clear pending changes after both views are refreshed
                    transferQueries.clearPendingChanges()
                }
            }
        }

    override suspend fun fullRefreshMaterializedViews(): Duration =
        withContext(Dispatchers.Default) {
            measureTime {
                transferQueries.transaction {
                    // Full refresh - delete and rebuild all data
                    transferQueries.refreshAccountBalances()
                    transferQueries.populateAccountBalances()
                    transferQueries.refreshRunningBalances()
                    transferQueries.populateRunningBalances()
                    // Clear pending changes to avoid redundant work
                    transferQueries.clearPendingChanges()
                }
            }
        }
}
