package com.moneymanager.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.MoneyManagerDatabase
import com.moneymanager.di.AppScope
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CategoryType
import com.moneymanager.domain.repository.CategoryRepository
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CategoryRepositoryImpl(
    private val database: MoneyManagerDatabase
) : CategoryRepository {

    private val queries = database.categoryQueries

    override fun getAllCategories(): Flow<List<Category>> =
        queries.selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { categories -> categories.map { it.toDomainModel() } }

    override fun getCategoryById(id: Long): Flow<Category?> =
        queries.selectById(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.toDomainModel() }

    override fun getCategoriesByType(type: CategoryType): Flow<List<Category>> =
        queries.selectByType(type.name)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { categories -> categories.map { it.toDomainModel() } }

    override fun getTopLevelCategories(): Flow<List<Category>> =
        queries.selectTopLevel()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { categories -> categories.map { it.toDomainModel() } }

    override fun getCategoriesByParent(parentId: Long): Flow<List<Category>> =
        queries.selectByParent(parentId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { categories -> categories.map { it.toDomainModel() } }

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

    override suspend fun updateCategory(category: Category) = withContext(Dispatchers.Default) {
        queries.update(
            name = category.name,
            color = category.color,
            icon = category.icon,
            parentId = category.parentId,
            isActive = if (category.isActive) 1 else 0,
            updatedAt = category.updatedAt.toEpochMilliseconds(),
            id = category.id
        )
    }

    override suspend fun deleteCategory(id: Long) = withContext(Dispatchers.Default) {
        queries.delete(id)
    }

    private fun com.moneymanager.database.Category.toDomainModel() = Category(
        id = id,
        name = name,
        type = CategoryType.valueOf(type),
        color = color,
        icon = icon,
        parentId = parentId,
        isActive = isActive == 1L,
        createdAt = Instant.fromEpochMilliseconds(createdAt),
        updatedAt = Instant.fromEpochMilliseconds(updatedAt)
    )
}
