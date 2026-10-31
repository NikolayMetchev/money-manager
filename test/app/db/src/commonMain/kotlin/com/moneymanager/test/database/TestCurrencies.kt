package com.moneymanager.test.database

import com.moneymanager.domain.model.CurrencyExportEntry

/**
 * Currencies seeded into fresh test databases. The app seeds none (it installs the catalog's list), but GBP is always
 * present (seeded with a fixed id by Schema.create), so together tests see GBP, USD and EUR.
 */
val testCurrencies: List<CurrencyExportEntry> =
    listOf(CurrencyExportEntry("USD", "US Dollar", 2), CurrencyExportEntry("EUR", "Euro", 2))
