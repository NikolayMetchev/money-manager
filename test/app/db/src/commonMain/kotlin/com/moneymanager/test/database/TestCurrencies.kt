package com.moneymanager.test.database

import com.moneymanager.currency.Currency

/**
 * Currencies seeded into fresh test databases when [createTestDatabaseManager] is called with
 * `seedAllCurrencies = false`. GBP is always present (seeded with a fixed id by Schema.create),
 * so together tests see GBP, USD and EUR. Seeding the full platform list (~230+ codes, each
 * firing audit triggers) dominated per-test DB setup time.
 */
internal val minimalTestCurrencies = listOf(Currency("USD"), Currency("EUR"))
