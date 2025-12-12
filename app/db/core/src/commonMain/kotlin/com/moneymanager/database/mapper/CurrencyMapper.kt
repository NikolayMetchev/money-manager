@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import tech.mappie.api.ObjectMappie
import kotlin.uuid.Uuid
import com.moneymanager.database.sql.Currency as DbCurrency

object CurrencyMapper : ObjectMappie<DbCurrency, Currency>() {
    override fun map(from: DbCurrency) =
        mapping {
            Currency::id fromValue CurrencyId(Uuid.parse(from.id))
        }
}
