package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CategoryBalance
import kotlinx.coroutines.flow.Flow

interface CategoryReadRepository {
    fun getAllCategories(): Flow<List<Category>>

    fun getCategoryBalances(): Flow<List<CategoryBalance>>

    fun getCategoryById(id: Long): Flow<Category?>

    fun getTopLevelCategories(): Flow<List<Category>>

    fun getCategoriesByParent(parentId: Long): Flow<List<Category>>
}
