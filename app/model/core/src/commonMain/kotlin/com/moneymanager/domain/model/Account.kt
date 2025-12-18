@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

data class Account(
    val id: AccountId,
    val name: String,
    val openingDate: Instant,
    val categoryId: Long = Category.UNCATEGORIZED_ID,
)

@Serializable
@JvmInline
value class AccountId(val id: Long) {
    override fun toString() = id.toString()
}
