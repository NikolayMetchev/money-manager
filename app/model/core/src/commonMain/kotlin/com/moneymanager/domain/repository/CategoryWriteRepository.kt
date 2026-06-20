package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.Source

interface CategoryWriteRepository : CategoryReadRepository {
    suspend fun createCategory(
        category: Category,
        source: Source,
    ): Long

    suspend fun updateCategory(
        category: Category,
        source: Source,
    )

    suspend fun deleteCategory(id: Long)
}
