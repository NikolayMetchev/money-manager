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
     * Refreshes all materialized views in the database incrementally.
     * This updates only the affected account-currency pairs tracked in PendingMaterializedViewChanges.
     *
     * This is the default refresh method and should be called after normal operations.
     * It's much faster than fullRefreshMaterializedViews for typical changes.
     *
     * @return The duration the operation took to complete
     */
    suspend fun refreshMaterializedViews(): Duration

    /**
     * Performs a full rebuild of all materialized views in the database.
     * This completely deletes and rebuilds AccountBalanceMaterializedView and
     * RunningBalanceMaterializedView from the Transfer table data.
     *
     * Use this for data integrity verification or after bulk operations.
     * For normal operations, use refreshMaterializedViews() instead (much faster).
     *
     * @return The duration the operation took to complete
     */
    suspend fun fullRefreshMaterializedViews(): Duration
}
