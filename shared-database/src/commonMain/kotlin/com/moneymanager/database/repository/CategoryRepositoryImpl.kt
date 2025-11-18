@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.CategoryMapper
import com.moneymanager.database.MoneyManagerDatabase
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CategoryType
import com.moneymanager.domain.repository.CategoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class CategoryRepositoryImpl(
    private val database: MoneyManagerDatabase
) : CategoryRepository {

    private val queries = database.categoryQueries

    override fun getAllCategories(): Flow<List<Category>> =
        queries.selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(CategoryMapper::mapList)

    override fun getCategoryById(id: Long): Flow<Category?> =
        queries.selectById(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(CategoryMapper::map) }

    override fun getCategoriesByType(type: CategoryType): Flow<List<Category>> =
        queries.selectByType(type.name)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(CategoryMapper::mapList)

    override fun getTopLevelCategories(): Flow<List<Category>> =
        queries.selectTopLevel()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(CategoryMapper::mapList)

    override fun getCategoriesByParent(parentId: Long): Flow<List<Category>> =
        queries.selectByParent(parentId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(CategoryMapper::mapList)

    override suspend fun createCategory(category: Category): Long = withContext(Dispatchers.Default) {
        queries.insert(
            name = category.name,
            type = category.type.name,
            color = category.color,
            icon = category.icon,
            parentId = category.parentId,
            isActive = if (category.isActive) 1 else 0,
            createdAt = category.createdAt.toEpochMilliseconds(),
            updatedAt = category.updatedAt.toEpochMilliseconds()
        )
        queries.lastInsertRowId().executeAsOne()
    }

    override suspend fun updateCategory(category: Category): Unit = withContext(Dispatchers.Default) {
        queries.update(
            name = category.name,
            color = category.color,
            icon = category.icon,
            parentId = category.parentId,
            isActive = if (category.isActive) 1 else 0,
            updatedAt = category.updatedAt.toEpochMilliseconds(),
            id = category.id
        )
        Unit
    }

    override suspend fun deleteCategory(id: Long): Unit = withContext(Dispatchers.Default) {
        queries.delete(id)
        Unit
    }
}
