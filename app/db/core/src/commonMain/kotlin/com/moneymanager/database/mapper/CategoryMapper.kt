@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.mapper

import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CategoryType
import tech.mappie.api.ObjectMappie
import kotlin.time.Instant

object CategoryMapper : ObjectMappie<com.moneymanager.database.sql.Category, Category>() {
    override fun map(from: com.moneymanager.database.sql.Category) =
        mapping {
            Category::type fromValue CategoryType.valueOf(from.type)
            Category::isActive fromValue (from.isActive == 1L)
            Category::createdAt fromValue Instant.fromEpochMilliseconds(from.createdAt)
            Category::updatedAt fromValue Instant.fromEpochMilliseconds(from.updatedAt)
        }
}
