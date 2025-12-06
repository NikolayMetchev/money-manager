@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import kotlin.uuid.Uuid

object CurrencyMapper {
    fun map(entity: com.moneymanager.database.sql.Currency): Currency =
        Currency(
            id = CurrencyId(Uuid.parse(entity.id)),
            code = entity.code,
            name = entity.name,
        )

    fun mapList(entities: List<com.moneymanager.database.sql.Currency>): List<Currency> = entities.map(::map)
}
