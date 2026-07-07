package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.database.mapper.AssetRowMapper
import com.moneymanager.database.mapper.CategoryMapper
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CategoryBalance
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.repository.CategoryReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CategoryReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : CategoryReadRepository {
    private val selectQueries = database.categorySelectQueries

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
                    val asset =
                        AssetRowMapper.buildAsset(
                            id = row.asset_id,
                            code = row.asset_code,
                            name = row.asset_name,
                            scaleFactor = row.asset_scale_factor,
                            kind = row.asset_kind,
                        )
                    CategoryBalance(
                        categoryId = row.category_id,
                        balance = Money(BigInteger(row.balance), asset),
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
}
