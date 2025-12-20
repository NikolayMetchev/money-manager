@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.mapper

import kotlin.time.Instant

interface InstantConversions {
    fun toInstant(epochMillis: Long): Instant = Instant.fromEpochMilliseconds(epochMillis)
}
