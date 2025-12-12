@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model

/**
 * Represents the aggregated balance for a category.
 * Includes the sum of all account balances for this category
 * plus all descendant categories, grouped by currency.
 *
 * @property categoryId The category this balance belongs to
 * @property balance The aggregated balance amount (includes currency)
 */
data class CategoryBalance(
    val categoryId: Long,
    val balance: Money,
)
