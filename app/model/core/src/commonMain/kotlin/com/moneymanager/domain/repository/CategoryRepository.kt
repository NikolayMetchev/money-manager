package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Category
import kotlinx.coroutines.flow.Flow

interface CategoryRepository {
    fun getAllCategories(): Flow<List<Category>>

    fun getCategoryById(id: Long): Flow<Category?>

    fun getTopLevelCategories(): Flow<List<Category>>

    fun getCategoriesByParent(parentId: Long): Flow<List<Category>>

    suspend fun createCategory(category: Category): Long

    suspend fun updateCategory(category: Category)

    suspend fun deleteCategory(id: Long)
}
