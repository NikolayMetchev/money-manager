package com.moneymanager.database.repository

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.recordSource
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CategoryWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CategoryWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    reader: CategoryReadRepository,
) : CategoryWriteRepository,
    CategoryReadRepository by reader {
    private val selectQueries = database.categorySelectQueries
    private val writeQueries = database.categoryWriteQueries

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
