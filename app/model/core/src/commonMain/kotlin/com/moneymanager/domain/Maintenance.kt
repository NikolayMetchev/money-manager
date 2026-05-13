package com.moneymanager.domain

import kotlin.time.Duration

interface Maintenance {
    suspend fun reindex(): Duration

    suspend fun vacuum(): Duration

    suspend fun analyze(): Duration

    suspend fun refreshMaterializedViews(): Duration

    suspend fun fullRefreshMaterializedViews(): Duration
}
