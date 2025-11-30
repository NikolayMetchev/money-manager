@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

data class Account(
    val id: Long = 0,
    val name: String,
    val asset: Asset,
    val initialBalance: Double = 0.0,
    val openingDate: Instant,
)
