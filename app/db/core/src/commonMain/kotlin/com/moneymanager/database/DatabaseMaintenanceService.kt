package com.moneymanager.database

import kotlin.time.Duration

/**
 * Service for performing database maintenance operations.
 * These operations help optimize database performance and reclaim storage space.
 */
interface DatabaseMaintenanceService {
    /**
     * Rebuilds all indices in the database.
     * This can help improve query performance if indices have become fragmented.
     *
     * @return The duration the operation took to complete
     */
    suspend fun reindex(): Duration

    /**
     * Rebuilds the entire database file, repacking it into a minimal amount of disk space.
     * This reclaims unused space and can improve database performance.
     *
     * Note: VACUUM requires exclusive access to the database and can take significant time
     * on large databases.
     *
     * @return The duration the operation took to complete
     */
    suspend fun vacuum(): Duration

    /**
     * Gathers statistics about indices and stores them in the database.
     * These statistics help the query optimizer make better decisions.
     *
     * @return The duration the operation took to complete
     */
    suspend fun analyze(): Duration

    /**
     * Refreshes all materialized views in the database.
     * This rebuilds AccountBalanceMaterializedView and RunningBalanceMaterializedView
     * from the Transfer table data.
     *
     * Call this after bulk inserts or when materialized view data becomes stale.
     *
     * @return The duration the operation took to complete
     */
    suspend fun refreshMaterializedViews(): Duration
}
