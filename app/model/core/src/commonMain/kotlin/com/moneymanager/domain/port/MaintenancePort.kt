package com.moneymanager.domain.port

import kotlin.time.Duration

interface MaintenancePort {
    suspend fun reindex(): Duration

    suspend fun vacuum(): Duration

    suspend fun analyze(): Duration

    suspend fun refreshMaterializedViews(): Duration

    suspend fun fullRefreshMaterializedViews(): Duration
}
