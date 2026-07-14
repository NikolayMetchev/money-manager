package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.CategoryReadRepository

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
