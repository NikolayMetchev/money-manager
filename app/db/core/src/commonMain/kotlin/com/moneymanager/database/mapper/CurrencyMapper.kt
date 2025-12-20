package com.moneymanager.database.mapper

import com.moneymanager.domain.model.Currency
import tech.mappie.api.ObjectMappie
import com.moneymanager.database.sql.Currency as DbCurrency

object CurrencyMapper : ObjectMappie<DbCurrency, Currency>(), IdConversions
