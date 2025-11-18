@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

data class Account(
    val id: Long = 0,
    val name: String,
    val type: AccountType,
    val currency: String = "USD",
    val initialBalance: Double = 0.0,
    val currentBalance: Double = 0.0,
    val color: String? = null,
    val icon: String? = null,
    val isActive: Boolean = true,
    val createdAt: Instant,
    val updatedAt: Instant
)
