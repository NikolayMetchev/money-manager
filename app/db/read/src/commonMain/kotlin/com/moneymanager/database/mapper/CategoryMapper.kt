package com.moneymanager.database.mapper

import com.moneymanager.domain.model.Category
import tech.mappie.api.ObjectMappie

// Written out: Mappie (2.4.21) also matches the internal `var _hashCode` Kotlin/Wasm's Any declares when both classes
// come from other modules, and the wasm link then can't find its accessors.
object CategoryMapper : ObjectMappie<com.moneymanager.database.sql.category.Category, Category>() {
    override fun map(from: com.moneymanager.database.sql.category.Category): Category =
        Category(
            id = from.id,
            name = from.name,
            parentId = from.parent_id,
        )
}
