package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.CategoryMapper
import com.moneymanager.database.recordSource
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CategoryBalance
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.CategoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class CategoryRepositoryImpl(
    database: MoneyManagerDatabase,
    private val deviceId: DeviceId,
) : CategoryRepository {
    private val selectQueries = database.categorySelectQueries
    private val writeQueries = database.categoryWriteQueries
    private val database = database

    override fun getAllCategories(): Flow<List<Category>> =
        selectQueries
            .selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(CategoryMapper::mapList)

    override fun getCategoryBalances(): Flow<List<CategoryBalance>> =
        selectQueries
            .selectAllCategoryBalances()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { row ->
                    val currency =
                        Currency(
                            id = CurrencyId(row.currency_id),
                            code = row.currency_code,
                            name = row.currency_name,
                            scaleFactor = row.currency_scale_factor,
                        )
                    CategoryBalance(
                        categoryId = row.category_id,
                        balance = Money(row.balance ?: 0, currency),
                    )
                }
            }

    override fun getCategoryById(id: Long): Flow<Category?> =
        selectQueries
            .selectById(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(CategoryMapper::map) }

    override fun getTopLevelCategories(): Flow<List<Category>> =
        selectQueries
            .selectTopLevel()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(CategoryMapper::mapList)

    override fun getCategoriesByParent(parentId: Long): Flow<List<Category>> =
        selectQueries
            .selectByParent(parentId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(CategoryMapper::mapList)

    override suspend fun createCategory(
        category: Category,
        source: Source,
    ): Long =
        withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                writeQueries.insert(
                    name = category.name,
                    parent_id = category.parentId,
                )
                val id = writeQueries.lastInsertRowId().executeAsOne()
                database.recordSource(deviceId, EntityType.CATEGORY, id, 1L, source)
                id
            }
        }

    override suspend fun updateCategory(
        category: Category,
        source: Source,
    ): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                writeQueries.update(
                    name = category.name,
                    parent_id = category.parentId,
                    id = category.id,
                )
                val revision = selectQueries.selectRevisionById(category.id).executeAsOne()
                database.recordSource(deviceId, EntityType.CATEGORY, category.id, revision, source)
            }
        }

    override suspend fun deleteCategory(id: Long): Unit =
        withContext(Dispatchers.Default) {
            // Trigger handles updating children's parentId before delete
            writeQueries.delete(id)
        }
}
