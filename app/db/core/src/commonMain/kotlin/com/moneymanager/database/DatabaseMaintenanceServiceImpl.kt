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
    private val queries = database.maintenanceQueries

    override suspend fun reindex(): Duration =
        withContext(Dispatchers.Default) {
            measureTime {
                queries.reindex()
            }
        }

    override suspend fun vacuum(): Duration =
        withContext(Dispatchers.Default) {
            measureTime {
                queries.vacuum()
            }
        }

    override suspend fun analyze(): Duration =
        withContext(Dispatchers.Default) {
            measureTime {
                queries.analyze()
            }
        }
}
