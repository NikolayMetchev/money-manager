@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

data class Category(
    val id: Long = 0,
    val name: String,
    val type: CategoryType,
    val color: String? = null,
    val icon: String? = null,
    val parentId: Long? = null,
    val isActive: Boolean = true,
    val createdAt: Instant,
    val updatedAt: Instant,
)
