@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

data class Account(
    val id: AccountId,
    val name: String,
    val openingDate: Instant,
)

@JvmInline
value class AccountId(val id: Long)
