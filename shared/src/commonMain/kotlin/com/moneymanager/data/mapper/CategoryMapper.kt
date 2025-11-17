package com.moneymanager.data.mapper

import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CategoryType
import kotlinx.datetime.Instant
import tech.mappie.api.ObjectMappie

object CategoryMapper : ObjectMappie<com.moneymanager.database.Category, Category>() {
    override fun map(from: com.moneymanager.database.Category) = mapping {
        Category::type fromValue CategoryType.valueOf(from.type)
        Category::isActive fromValue (from.isActive == 1L)
        Category::createdAt fromValue Instant.fromEpochMilliseconds(from.createdAt)
        Category::updatedAt fromValue Instant.fromEpochMilliseconds(from.updatedAt)
    }
}
