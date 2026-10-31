package com.moneymanager.test.database

import com.moneymanager.domain.model.CurrencyExportEntry

/**
 * Currencies seeded into fresh test databases, as a person would install them from the catalog's list (the app seeds
 * none). GBP comes first, so in a fresh test database it is the first asset, `CurrencyId(1)`.
 */
val testCurrencies: List<CurrencyExportEntry> =
    listOf(
        CurrencyExportEntry("GBP", "British Pound", 2),
        CurrencyExportEntry("USD", "US Dollar", 2),
        CurrencyExportEntry("EUR", "Euro", 2),
    )
