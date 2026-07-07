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
                // category_balance_view is non-aggregating (one row per ancestor category × descendant
                // account × asset), so sum per (category, asset) here in BigInteger — TEXT amounts can't
                // be SUMmed in SQL.
                list
                    .groupBy { it.category_id to it.asset_id }
                    .map { (_, rows) ->
                        val first = rows.first()
                        val asset =
                            AssetRowMapper.buildAsset(
                                id = first.asset_id,
                                code = first.asset_code,
                                name = first.asset_name,
                                scaleFactor = first.asset_scale_factor,
                                kind = first.asset_kind,
                            )
                        val total = rows.fold(BigInteger.ZERO) { acc, r -> acc + BigInteger(r.balance) }
                        CategoryBalance(
                            categoryId = first.category_id,
                            balance = Money(total, asset),
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
